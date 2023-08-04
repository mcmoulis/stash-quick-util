package com.mcms.study.tools.stash;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.SSLConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.response.ResponseBody;
import io.restassured.specification.RequestSpecification;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class StashHelper {

    private static final int PAGE_SIZE = 5000;

    private static final String PROJECTS_API_URI = "projects";
    private static final String REPOS_API_URI = PROJECTS_API_URI + "/{projectName}/repos";
    private static final String DEF_BRANCH_API_URI = REPOS_API_URI + "/{repoName}/branches/default";

    private static final String NEW_LINE = "\n";

    private static String stashServer;
    private static String projectName;
    private static String localDir;

    private static RequestSpecification getBaseRequest(String username, String password) {
        return RestAssured.with()
                .config(RestAssured.config()
                        .sslConfig(new SSLConfig().relaxedHTTPSValidation())
                        .httpClient(new HttpClientConfig().reuseHttpClientInstance()))
                .baseUri(stashServer)
                .accept(ContentType.JSON)
                .auth().preemptive().basic(username, password);
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        Properties config = new Properties();
        ClassPathResource resource = new ClassPathResource("config.properties");
        if (resource.exists()) {
            PropertiesLoaderUtils.fillProperties(config, resource);
        }

        String username = System.getProperty("user.name");
        System.out.println("Username:" + username);

        System.out.print("Password:");
        String password = scanner.reset().nextLine();

        System.out.print("Bitbucket url:");
        if (config.containsKey("stashServer")) {
            stashServer = config.getProperty("stashServer");
            System.out.print(stashServer);
        } else {
            stashServer = scanner.reset().nextLine().trim();
        }

        System.out.print("Bitbucket project (all project if no value):");
        projectName = scanner.reset().nextLine().trim();

        System.out.print("Local directory:");
        if (config.containsKey("localDir")) {
            localDir = config.getProperty("localDir");
            System.out.print(localDir);
        } else {
            localDir = scanner.reset().nextLine().trim();
        }

        System.out.printf("""
                \nUsername: %s
                Password: %s
                Bitbucket URL: %s
                Project Name: %s
                Local Directory: %s
                \n""", username, password, stashServer, projectName, localDir);

        StopWatch stopWatch = new StopWatch();
        stopWatch.start("Download projects list");

        RequestSpecification request = getBaseRequest(username, password);
        int projectsCount = 0;
        List<Project> projectList = new ArrayList<>();
        if (StringUtils.hasText(projectName)) {
            projectsCount = 1;
            projectList.add(new Project(projectName));
        } else {
            JsonPath projectsJson = request
                    .queryParam("start", 0)
                    .queryParam("limit", PAGE_SIZE)
                    .get(PROJECTS_API_URI)
                    .body()
                    .jsonPath();

            projectsCount = projectsJson.get("values.size()");
            projectList = IntStream.range(0, projectsCount)
                    .mapToObj(index ->
                            new Project(projectsJson.get("values[" + index + "].key"))
                    ).sorted()
                    .collect(Collectors.toList());
        }

        System.out.println("Total accessible projects: " + projectsCount);
        StringBuffer cloneScriptBuffer = new StringBuffer();
        StringBuffer pullScriptBuffer = new StringBuffer();

        stopWatch.stop();
        stopWatch.start("Download repos list");

        projectList.stream()
                .forEach(project -> {
                    try {
                        JsonPath reposJsonPath = request
                                .pathParam("projectName", project.key)
                                .queryParam("start", 0)
                                .queryParam("limit", PAGE_SIZE)
                                .get(REPOS_API_URI)
                                .body()
                                .jsonPath();

                        int repoCount = reposJsonPath.get("values.size()");

                        System.out.println("Repos count for " + project.key + " is: " + repoCount);
                        IntStream.range(0, repoCount)
                                .forEach(index -> {
                                            String repoName = reposJsonPath.get("values[" + index + "].name");
                                            ResponseBody<Response> defaultRepoResponse = request
                                                    .pathParam("projectName", project.key)
                                                    .pathParam("repoName", repoName)
                                                    .queryParam("start", 0)
                                                    .queryParam("limit", PAGE_SIZE)
                                                    .get(DEF_BRANCH_API_URI);
                                            project.repos.put(
                                                    repoName,
                                                    new Repo(reposJsonPath.get("values[" + index
                                                            + "].links.clone.find{it.name == 'ssh'}.href"),
                                                            defaultRepoResponse != null
                                                                    && !defaultRepoResponse.asString().isEmpty()
                                                                    ? defaultRepoResponse.jsonPath().get("displayId")
                                                                    : "master")
                                            );
                                        }
                                );
                        prepareCloneScript(cloneScriptBuffer, project);
                        preparePullScript(pullScriptBuffer, project);
                    } catch (Exception e) {
                        System.out.println("Unable to fetch repos of project: " + project.key);
                        e.printStackTrace();
                    }
                });

        stopWatch.stop();
        stopWatch.start("Generate scritps");

        Files.write(Paths.get(projectName + "_git_clone_script.bat"), cloneScriptBuffer.toString().getBytes());
        Files.write(Paths.get(projectName + "_git_pull_script.bat"), pullScriptBuffer.toString().getBytes());

        stopWatch.stop();
        System.out.println("Total time taken in milliseconds: " + stopWatch.getTotalTimeSeconds());
        System.out.println("Details: \n" + stopWatch.prettyPrint());
    }

    private static void prepareCloneScript(StringBuffer cloneScriptBuffer, Project project) {
        final String projectName = project.key.toLowerCase();
        project.repos.entrySet().forEach(entry ->
                cloneScriptBuffer
                        .append("rem ").append(projectName).append("\\").append(entry.getKey()).append(NEW_LINE)
                        .append("mkdir ").append(localDir).append(projectName).append(NEW_LINE)
                        .append("cd ").append(localDir).append(projectName).append(NEW_LINE)
                        .append("git clone ").append(entry.getValue().sshLink).append(NEW_LINE)
                        .append(NEW_LINE).append(NEW_LINE)
        );
    }

    private static void preparePullScript(StringBuffer pullScriptBuffer, Project project) {
        project.repos.entrySet().forEach(entry ->
                pullScriptBuffer
                        .append("rem ").append(project.key).append("\\").append(entry.getKey()).append(NEW_LINE)
                        .append("cd ").append(localDir).append(project.key).append("\\").append(entry.getKey())
                        .append(NEW_LINE)
                        //.append("git pull --rebase --autostash origin ").append(entry.getValue().defaultBranch)
                        //.append(NEW_LINE)
                        .append("git checkout ").append(entry.getValue().defaultBranch).append(NEW_LINE)
                        .append("git fetch --prune --tags --progress \"origin\"").append(NEW_LINE)
                        .append("git pull --progress \"origin\"").append(NEW_LINE)
                        .append(NEW_LINE).append(NEW_LINE)
        );
    }

    private static class Project {

        public String key;
        public Map<String, Repo> repos;

        public Project(String key) {
            this.key = key;
            repos = new TreeMap<>();
        }
    }

    private static class Repo {
        public String sshLink;
        public String defaultBranch;

        public Repo(String sshLink, String defaultBranch) {
            this.sshLink = sshLink;
            this.defaultBranch = defaultBranch;
        }
    }

}

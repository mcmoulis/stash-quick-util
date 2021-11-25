package com.mcms.study.tools.stash;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.SSLConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;

public class StashHelper {

  private static final int PAGE_SIZE = 5000;

  private static final String PROJECTS_API_URI = "projects";
  private static final String REPOS_API_URI = PROJECTS_API_URI + "/{projectName}/repos";

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
    System.out.print("Username:");
    String username = scanner.nextLine();
    System.out.print("Password:");
    String password = scanner.nextLine();
    System.out.print("Bitbucket url:");
    stashServer = scanner.nextLine().trim();
    System.out.print("Bitbucket project (all project if no value):");
    projectName = scanner.nextLine().trim();
    System.out.print("Local directory:");
    localDir = scanner.nextLine().trim();

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
          )
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
                .forEach(index ->
                    project.repoCloneList.put(
                        reposJsonPath.get("values[" + index + "].name"),
                        reposJsonPath.get("values[" + index
                            + "].links.clone.find{it.name == 'ssh'}.href")
                    )
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

    Files.write(Paths.get("_git_clone_script.bat"), cloneScriptBuffer.toString().getBytes());
    Files.write(Paths.get("_git_pull_script.bat"), pullScriptBuffer.toString().getBytes());

    stopWatch.stop();
    System.out.println("Total time taken in milliseconds: " + stopWatch.getTotalTimeMillis());
    System.out.println("Details: \n" + stopWatch.prettyPrint());
  }

  private static void prepareCloneScript(StringBuffer cloneScriptBuffer, Project project) {
    project.repoCloneList.entrySet().forEach(entry ->
        cloneScriptBuffer
            .append("rem ").append(project.key).append("\\").append(entry.getKey()).append(NEW_LINE)
            .append("mkdir ").append(localDir).append(project.key).append(NEW_LINE)
            .append("cd ").append(localDir).append(project.key).append(NEW_LINE)
            .append("git clone ").append(entry.getValue()).append(NEW_LINE)
            .append(NEW_LINE).append(NEW_LINE)
    );
  }

  private static void preparePullScript(StringBuffer pullScriptBuffer, Project project) {
    project.repoCloneList.entrySet().forEach(entry ->
        pullScriptBuffer
            .append("rem ").append(project.key).append("\\").append(entry.getKey()).append(NEW_LINE)
            .append("cd ").append(localDir).append(project.key).append(NEW_LINE)
            .append("git fetch --prune --tags --progress \"origin\"").append(NEW_LINE)
            .append("git pull --progress \"origin\"").append(NEW_LINE)
            .append(NEW_LINE).append(NEW_LINE)
    );
  }

  private static class Project {

    public String key;
    public Map<String, String> repoCloneList;

    public Project(String key) {
      this.key = key;
      repoCloneList = new HashMap<>();
    }
  }
}
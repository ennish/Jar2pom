package com.ennio.grok;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class JarToPomConverter {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("用法: java -jar jar-to-pom.jar <jar文件目录> output.pom");
            return;
        }

        String jarDirPath = args[0];
        File jarDir = new File(jarDirPath);
        String output = args[1];
        if (!jarDir.isDirectory()) {
            System.out.println("错误: 指定的路径不是一个目录");
            return;
        }
         

        List<String> dependencies = new ArrayList<>();
        List<String> unresolvedJars = new ArrayList<>();

        // 遍历目录中的jar文件
        File[] jarFiles = jarDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jarFiles == null || jarFiles.length == 0) {
            System.out.println("目录中没有找到jar文件");
            return;
        }

        for (File jarFile : jarFiles) {
            try {
                // 计算jar文件的SHA-1校验和
                String sha1 = calculateSha1(jarFile);
                // 查询Maven中央仓库
                String dependency = resolveDependencyFromMavenCentral(sha1, jarFile.getName());
                if (dependency != null) {
                    dependencies.add(dependency);
                    System.out.println(dependency);
                } else {
                    unresolvedJars.add(jarFile.getName());
                }
            } catch (IOException e) {
                System.err.println("处理文件 " + jarFile.getName() + " 时出错: " + e.getMessage());
                unresolvedJars.add(jarFile.getName());
            }
        }

        
        // 输出结果
        gatherDependencies(dependencies, output);
    }

    // 计算文件的SHA-1校验和
    private static String calculateSha1(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return DigestUtils.sha1Hex(fis);
        }
    }

    // 从Maven中央仓库解析依赖信息
    private static String resolveDependencyFromMavenCentral(String sha1, String jarName) {
        String url = "https://search.maven.org/solrsearch/select?q=1:%22" + sha1 + "%22&rows=1&wt=json";
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            try (CloseableHttpResponse response = client.execute(request)) {
                String jsonResponse = EntityUtils.toString(response.getEntity());
                JSONObject json = new JSONObject(jsonResponse);
                JSONObject responseObj = json.getJSONObject("response");
                if (responseObj.getInt("numFound") > 0) {
                    JSONArray docs = responseObj.getJSONArray("docs");
                    JSONObject doc = docs.getJSONObject(0);
                    String groupId = doc.getString("g");
                    String artifactId = doc.getString("a");
                    String version = doc.getString("v");
                    return formatDependency(groupId, artifactId, version);
                }
            }
        } catch (Exception e) {
            System.err.println("查询 " + jarName + " 的依赖信息失败: " + e.getMessage());
        }
        return null;
    }

    // 格式化依赖为XML字符串
    private static String formatDependency(String groupId, String artifactId, String version) {
        return String.format(
                "    <dependency>\n" +
                "        <groupId>%s</groupId>\n" +
                "        <artifactId>%s</artifactId>\n" +
                "        <version>%s</version>\n" +
                "    </dependency>",
                groupId, artifactId, version);
    }

    // 输出依赖和未解析的jar文件
    private static void printDependencies(List<String> dependencies, List<String> unresolvedJars) {
        if (!dependencies.isEmpty()) {
            System.out.println("<dependencies>");

            for (String dependency : dependencies) {
                System.out.println(dependency);
            }
            System.out.println("</dependencies>");
        } else {
            System.out.println("未找到任何可识别的依赖");
        }

        if (!unresolvedJars.isEmpty()) {
            System.out.println("\n以下jar文件无法自动识别，请手动添加依赖信息：");
            for (String jarName : unresolvedJars) {
                System.out.println("- " + jarName);
            }
        }
    }

    private static void gatherDependencies(List<String> dependencies,  String outputFile) throws IOException {
        if (!dependencies.isEmpty()) {
            try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
                writer.append("<dependencies>").append("\r\n");

                for (String dependency : dependencies) {
                    writer.append(dependency).append("\r\n");
                }
                writer.append("</dependencies>");

            }
            
        } else {
            System.out.println("未找到任何可识别的依赖");
        }
    }
}

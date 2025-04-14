package com.ennio.deepseek;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.stream.Stream;
import java.util.zip.*;

class Dependency {
    private String groupId;
    private String artifactId;
    private String version;

    public Dependency(String groupId, String artifactId, String version) {
        this.groupId = groupId != null ? groupId : "unknown";
        this.artifactId = artifactId;
        this.version = version;
    }

    public String getGroupId() { return groupId; }
    public String getArtifactId() { return artifactId; }
    public String getVersion() { return version; }
}

public class JarToPomConverter {
    
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java JarToPomConverter <inputDirectory> <outputFile>");
            System.exit(1);
        }

        File inputDir = new File(args[0]);
        File outputFile = new File(args[1]);

        if (!inputDir.isDirectory()) {
            System.err.println("Invalid input directory: " + args[0]);
            System.exit(1);
        }

        Set<Dependency> dependencies = new LinkedHashSet<>(); // 自动去重
        processDirectory(inputDir, dependencies);
        generatePomFile(new ArrayList<>(dependencies), outputFile);
    }

    private static void processDirectory(File dir, Set<Dependency> dependencies) {
        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                processDirectory(file, dependencies);
            } else if (file.getName().endsWith(".jar")) {
                try {
                    Dependency dep = parseJar(file);
                    if (dep != null) {
                        dependencies.add(dep);
                    }
                } catch (IOException e) {
                    System.err.println("Error processing: " + file.getName());
                    e.printStackTrace();
                }
            }
        }
    }

    private static Dependency parseJar(File jarFile) throws IOException {
        System.out.println("\nProcessing: " + jarFile.getName());
        try (JarFile jar = new JarFile(jarFile)) {
            // 优先解析pom.properties
            Dependency dep = findFromPomProperties(jar);
            if (dep != null) {
                System.out.println("  Identified via pom.properties");
                return dep;
            }

            // 次优解析Manifest
            dep = findFromManifest(jar);
            if (dep != null) {
                System.out.println("  Identified via MANIFEST");
                return dep;
            }

            // 最后解析文件名
            System.out.println("  Attempting filename analysis...");
            return parseFromFilename(jarFile.getName());
        }
    }

    private static Dependency findFromPomProperties(JarFile jar) throws IOException {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String path = entry.getName();
            
            if (path.startsWith("META-INF/maven/") && 
                path.endsWith("/pom.properties")) {
                
                // 从路径解析groupId和artifactId
                String[] parts = path.split("/");
                if (parts.length < 5) continue;
                
                int artifactIndex = parts.length - 2; // artifactId位置
                String[] groupParts = Arrays.copyOfRange(
                    parts, 2, parts.length - 3); // groupId部分
                String groupIdFromPath = String.join(".", groupParts);
                String artifactIdFromPath = parts[artifactIndex];

                // 读取properties文件
                Properties props = new Properties();
                try (InputStream is = jar.getInputStream(entry)) {
                    props.load(is);
                }

                // 合并路径和properties中的信息
                String groupId = props.getProperty("groupId", groupIdFromPath);
                String artifactId = props.getProperty("artifactId", artifactIdFromPath);
                String version = props.getProperty("version");

                if (artifactId != null && version != null) {
                    return new Dependency(groupId, artifactId, version);
                }
            }
        }
        return null;
    }

    private static Dependency findFromManifest(JarFile jar) throws IOException {
        Manifest manifest = jar.getManifest();
        if (manifest == null) return null;

        Attributes attrs = manifest.getMainAttributes();
        String groupId = null;
        String artifactId = null;
        String version = null;

        // 多属性识别groupId
        groupId = Stream.of("Implementation-Vendor-Id", "Implementation-Vendor", 
                          "Bundle-Vendor", "Bundle-SymbolicName")
                .map(attrs::getValue)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        // 特殊处理Bundle-SymbolicName
        String symbolicName = attrs.getValue("Bundle-SymbolicName");
        if (symbolicName != null) {
            String[] parts = symbolicName.split("[;]");
            if (parts.length > 0) {
                String[] idParts = parts[0].split("\\.");
                if (idParts.length > 1) {
                    artifactId = idParts[idParts.length-1];
                    if (groupId == null) {
                        groupId = String.join(".", 
                            Arrays.copyOf(idParts, idParts.length-1));
                    }
                }
            }
        }

        // 识别artifactId
        if (artifactId == null) {
            artifactId = Stream.of("Implementation-Title", "Bundle-Name", 
                                 "Automatic-Module-Name")
                    .map(attrs::getValue)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        // 识别版本
        version = Stream.of("Implementation-Version", "Bundle-Version")
                .map(attrs::getValue)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        if (artifactId != null && version != null) {
            return new Dependency(
                groupId != null ? groupId : "unknown", 
                artifactId, 
                version
            );
        }
        return null;
    }

    private static Dependency parseFromFilename(String filename) {
        String baseName = filename.replaceAll("\\.jar$", "");
        String[] parts = baseName.split("-");
        
        if (parts.length < 2) return null;
        
        // 从后往前查找版本号
        for (int i = parts.length-1; i > 0; i--) {
            String versionCandidate = String.join("-", 
                Arrays.copyOfRange(parts, i, parts.length));
            
            if (isValidVersion(versionCandidate)) {
                String artifactId = String.join("-", 
                    Arrays.copyOfRange(parts, 0, i));
                return new Dependency("unknown", artifactId, versionCandidate);
            }
        }
        return null;
    }

    private static boolean isValidVersion(String version) {
        // 版本号规则：以数字开头，允许包含字母、数字、.-符号
        return version.matches("^\\d+[a-zA-Z0-9.-]*$");
    }

    private static void generatePomFile(List<Dependency> dependencies, File outputFile) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            writer.println("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"");
            writer.println("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
            writer.println("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0");
            writer.println("         http://maven.apache.org/xsd/maven-4.0.0.xsd\">");
            writer.println("    <modelVersion>4.0.0</modelVersion>");
            writer.println("    <dependencies>");

            for (Dependency dep : dependencies) {
                writer.printf("        <dependency>%n");
                writer.printf("            <groupId>%s</groupId>%n", dep.getGroupId());
                writer.printf("            <artifactId>%s</artifactId>%n", dep.getArtifactId());
                writer.printf("            <version>%s</version>%n", dep.getVersion());
                writer.printf("        </dependency>%n");
            }

            writer.println("    </dependencies>");
            writer.println("</project>");
        } catch (IOException e) {
            System.err.println("Error writing POM file: " + e.getMessage());
        }
    }
}
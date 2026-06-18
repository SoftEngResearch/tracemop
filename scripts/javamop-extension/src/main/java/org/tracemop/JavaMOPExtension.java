package org.tracemop;

import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.model.ConfigurationContainer;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.List;

@Named
@Singleton
public class JavaMOPExtension extends AbstractEventSpy {
    private static final String MOP_AGENT_STRING = "-javaagent:${settings.localRepository}/javamop-agent/" +
            "javamop-agent/1.0/javamop-agent-1.0.jar";

    private static String getAddOpens() {
        int version = Runtime.version().feature();
        if (version >= 9) {
            String addOpens = " --add-opens java.base/java.lang=ALL-UNNAMED"
                 + " --add-opens java.base/java.lang.reflect=ALL-UNNAMED"
                 + " --add-opens java.base/java.util=ALL-UNNAMED"
                 + " --add-opens java.base/java.util.stream=ALL-UNNAMED"
                 + " --add-opens java.base/java.util.concurrent=ALL-UNNAMED"
                 + " --add-opens java.base/java.io=ALL-UNNAMED"
                 + " --add-opens java.base/java.nio=ALL-UNNAMED"
                 + " --add-opens java.base/java.net=ALL-UNNAMED"
                 + " --add-opens java.base/java.text=ALL-UNNAMED";
            String nativeIndexingTree = System.getenv("NATIVE_INDEXING_TREE");
            String mopNativeIndexingTree = System.getenv("MOP_NATIVE_INDEXING_TREE");
            if ("true".equalsIgnoreCase(nativeIndexingTree) || "1".equals(nativeIndexingTree)
                    || "true".equalsIgnoreCase(mopNativeIndexingTree) || "1".equals(mopNativeIndexingTree)) {
                addOpens += " --add-opens java.base/java.lang.rv=ALL-UNNAMED";
            }
            return addOpens;
        }
        return "";
    }

    private void updateConfig(Xpp3Dom config) {
        Xpp3Dom argLine = config.getChild("argLine");

        String jvmOptions = System.getenv("ARG_LINE") != null ? System.getenv("ARG_LINE") : "";
        boolean collectTraces = System.getenv("COLLECT_TRACES") != null && System.getenv("COLLECT_TRACES").equals("1");
        boolean ajcCache = System.getenv("AJC_CACHE") != null && System.getenv("AJC_CACHE").equals("1");
        if (collectTraces)
            jvmOptions = jvmOptions + " -Xmx500g -XX:-UseGCOverheadLimit";
        if (ajcCache)
            jvmOptions = jvmOptions + " -Daj.weaving.cache.enabled=true -Daj.weaving.cache.dir=/tmp/aspectj-cache/";
        jvmOptions = jvmOptions + getAddOpens();

        String agentPath = System.getenv("MOP_AGENT_PATH") != null ? System.getenv("MOP_AGENT_PATH") : MOP_AGENT_STRING;

        if (argLine != null) {
            if (!jvmOptions.isEmpty()) {
                argLine.setValue(argLine.getValue() + " " + agentPath + jvmOptions);
            } else {
                argLine.setValue(argLine.getValue() + " " + agentPath);
            }
        } else {
            argLine = new Xpp3Dom("argLine");
            if (!jvmOptions.isEmpty()) {
                argLine.setValue(agentPath + jvmOptions);
            } else {
                argLine.setValue(agentPath);
            }
            config.addChild(argLine);
        }
    }

    private void updateSurefireVersion(Plugin plugin) {
        if (!plugin.getGroupId().equals("org.apache.maven.plugins") ||
                !plugin.getArtifactId().equals("maven-surefire-plugin")) {
            // Not Surefire
            return;
        }

        if (System.getenv("SUREFIRE_VERSION") != null) {
            plugin.setVersion(System.getenv("SUREFIRE_VERSION"));
        } else {
            // getVersion will return null for project romix/java-concurrent-hash-trie-map
            String pluginVersion = plugin.getVersion() == null ? "0" : plugin.getVersion();
            ComparableVersion surefireVersion = new ComparableVersion(pluginVersion);
            ComparableVersion reasonableVersion = new ComparableVersion("3.1.2");
            if (surefireVersion.compareTo(reasonableVersion) < 0) {
                // Surefire is outdated, update it to `reasonableVersion`
                plugin.setVersion("3.1.2");
            }
        }

        System.out.println("Changed surefire version to " + plugin.getVersion());
    }

    private void checkAndUpdateConfiguration(ConfigurationContainer container) {
        Xpp3Dom configNode = (Xpp3Dom) container.getConfiguration();
        if (configNode == null) {
            configNode = new Xpp3Dom("configuration");
            container.setConfiguration(configNode);
        }
        updateConfig(configNode);
    }

    private void updateSurefire(MavenProject project) {
        for (Plugin plugin : project.getBuildPlugins()) {
            if (plugin.getGroupId().equals("org.apache.maven.plugins") &&
                    plugin.getArtifactId().equals("maven-surefire-plugin")) {
                System.out.println("JavaMOPExtension: checking surefire version...");
                updateSurefireVersion(plugin);

                if (System.getenv("ADD_AGENT") != null && System.getenv("ADD_AGENT").equals("0"))
                    continue;

                System.out.println("JavaMOPExtension: checking agent...");
                checkAndUpdateConfiguration(plugin);

                for (PluginExecution exe : plugin.getExecutions()) {
                    checkAndUpdateConfiguration(exe);
                }
            }
        }
    }

    @Override
    public void onEvent(Object event) {
        if (event instanceof ExecutionEvent) {
            ExecutionEvent e = (ExecutionEvent) event;
            if (e.getType() == ExecutionEvent.Type.SessionStarted) {
                List<MavenProject> sortedProjects = e.getSession().getProjectDependencyGraph().getSortedProjects();
                for (MavenProject project : sortedProjects) {
                    updateSurefire(project);
                }
            }
        }
    }
}

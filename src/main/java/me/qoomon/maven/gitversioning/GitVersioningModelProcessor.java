package me.qoomon.maven.gitversioning;

import de.pdark.decentxml.Document;
import de.pdark.decentxml.Element;
import me.qoomon.gitversioning.commons.GitDescription;
import me.qoomon.gitversioning.commons.GitSituation;
import me.qoomon.gitversioning.commons.Lazy;
import me.qoomon.maven.gitversioning.Configuration.RefPatchDescription;
import org.apache.maven.building.Source;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.*;
import org.apache.maven.model.building.DefaultModelProcessor;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.session.scope.internal.SessionScope;
import org.slf4j.Logger;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static java.lang.Math.*;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.*;
import static me.qoomon.gitversioning.commons.StringUtil.*;
import static me.qoomon.maven.gitversioning.GitVersioningMojo.asPlugin;
import static me.qoomon.maven.gitversioning.MavenUtil.*;
import static org.apache.maven.shared.utils.StringUtils.*;
import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Replacement for {@link ModelProcessor} to adapt versions.
 */
@Named("core-default")
@Singleton
@SuppressWarnings("CdiInjectionPointsInspection")
public class GitVersioningModelProcessor extends DefaultModelProcessor {

    final private Logger logger = getLogger(GitVersioningModelProcessor.class);

    @Inject
    private SessionScope sessionScope;

    @Inject
    private ContextProvider contextProvider;

    private boolean initialized = false;
    private boolean disabled = false;

    private Map<String, Supplier<String>> globalFormatPlaceholderMap;
    private Map<String, String> gitProjectProperties;
    private Set<GAV> relatedProjects;


    // ---- other fields -----------------------------------------------------------------------------------------------

    private final Map<File, Model> sessionModelCache = new HashMap<>();


    @Override
    public Model read(File input, Map<String, ?> options) throws IOException {
        // clone model before return to prevent concurrency issues
        return processModel(super.read(input, options), options).clone();
    }

    @Override
    public Model read(Reader input, Map<String, ?> options) throws IOException {
        // clone model before return to prevent concurrency issues
        return processModel(super.read(input, options), options).clone();
    }

    @Override
    public Model read(InputStream input, Map<String, ?> options) throws IOException {
        // clone model before return to prevent concurrency issues
        return processModel(super.read(input, options), options).clone();
    }


    private void init(Model projectModel) throws IOException {
        logger.info("");
        logger.info(extensionLogHeader(BuildProperties.projectGAV()));

        logger.debug("execution root directory: " + contextProvider.getMavenSession().getExecutionRootDirectory());

        // check if extension is disabled by command option
        disabled = contextProvider.getDisableOption();
        if (disabled) {
            logger.info("skip - versioning is disabled by command option");
            return;
        }

        if (logger.isDebugEnabled()) {
            GitSituation gitSituation = contextProvider.getGitSituation();
            logger.debug("git situation:");
            logger.debug("  root directory: " + gitSituation.getRootDirectory());
            logger.debug("  head commit: " + gitSituation.getRev());
            logger.debug("  head commit timestamp: " + gitSituation.getTimestamp());
            logger.debug("  head branch: " + gitSituation.getBranch());
            logger.debug("  head tags: " + gitSituation.getTags());
            logger.debug("  head description: " + gitSituation.getDescription());
        }

        // determine git version details
        RefPatchMatch patchMatch = contextProvider.getPatchMatch();
        if (patchMatch == null) {
            GitSituation gitSituation = contextProvider.getGitSituation();
            logger.warn("skip - no matching <ref> configuration and no <rev> configuration defined");
            logger.warn("git refs:");
            logger.warn("  branch: " + gitSituation.getBranch());
            logger.warn("  tags: " + gitSituation.getTags());
            logger.warn("defined ref configurations:");
            contextProvider.getConfiguration().refs.list
                    .forEach(ref -> logger.warn("  " + rightPad(ref.type.name(), 6) + " - pattern: " + ref.pattern));
            disabled = true;
            return;
        }

        logger.info("matching ref: " + patchMatch.getRefType().name() + " - " + patchMatch.getRefName());
        final RefPatchDescription patchDescription = patchMatch.getPatchDescription();
        logger.info("ref configuration: " + patchMatch.getRefType().name() + " - pattern: " + patchDescription.pattern);
        if (patchDescription.describeTagPattern != null && !patchDescription.describeTagPattern.pattern().equals(".*")) {
            logger.info("  describeTagPattern: " + patchDescription.describeTagPattern);
        }
        if (patchDescription.version != null) {
            logger.info("  version: " + patchDescription.version);
        }
        if (!patchDescription.properties.isEmpty()) {
            logger.info("  properties: " + patchDescription.version);
            patchDescription.properties.forEach((key, value) -> logger.info("    " + key + " - " + value));
        }

        if (contextProvider.getUpdatePomOption()) {
            logger.info("  updatePom: " + contextProvider.getUpdatePomOption());
        }

        globalFormatPlaceholderMap = generateGlobalFormatPlaceholderMap(contextProvider);
        gitProjectProperties = generateGitProjectProperties(contextProvider);

        // determine related projects
        relatedProjects = determineRelatedProjects(projectModel);
        if (logger.isDebugEnabled()) {
            logger.debug(buffer().strong("related projects:").toString());
            relatedProjects.forEach(gav -> logger.debug("  " + gav));
        }

        logger.info("");
    }

    // ---- model processing -------------------------------------------------------------------------------------------

    public Model processModel(Model projectModel, Map<String, ?> options) throws IOException {
        // set model pom file
        final Source pomSource = (Source) options.get(ModelProcessor.SOURCE);
        if (pomSource != null) {
            projectModel.setPomFile(new File(pomSource.getLocation()));
        } else {
            logger.debug("skip model - no project model pom file");
            return projectModel;
        }

        if (!initialized) {
            init(projectModel);
            initialized = true;
        }

        if (disabled) {
            return projectModel;
        }

        GAV projectGAV = GAV.of(projectModel);
        if (projectGAV.getVersion() == null) {
            logger.debug("skip model - can not determine project version - " + projectModel.getPomFile());
            return projectModel;
        }

        if (!isRelatedProject(projectGAV)) {
            if (logger.isTraceEnabled()) {
                logger.trace("skip model - unrelated project - " + projectModel.getPomFile());
            }
            return projectModel;
        }

        File canonicalProjectPomFile = projectModel.getPomFile().getCanonicalFile();

        // return cached calculated project model if present
        Model cachedProjectModel = sessionModelCache.get(canonicalProjectPomFile);
        if (cachedProjectModel != null) {
            return cachedProjectModel;
        }

        // add current project model to session project models
        sessionModelCache.put(canonicalProjectPomFile, projectModel);

        // log project header
        logger.info(projectLogHeader(projectGAV));

        updateModel(projectModel, contextProvider.getPatchMatch().getPatchDescription());

        File gitVersionedPomFile = writePomFile(projectModel);
        if (contextProvider.getUpdatePomOption()) {
            logger.debug("updating original POM file");
            Files.copy(
                    gitVersionedPomFile.toPath(),
                    projectModel.getPomFile().toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        // git versioned pom can't be set as model pom right away,
        // because it will break plugins, that trying to update original pom file
        //   e.g. mvn versions:set -DnewVersion=1.0.0
        // That's why we need to add a build plugin that sets project pom file to git versioned pom file
        addBuildPlugin(projectModel);

        logger.info("");
        return projectModel;
    }

    private void updateModel(Model projectModel, RefPatchDescription patchDescription) {
        final GAV originalProjectGAV = GAV.of(projectModel);

        final String versionFormat = patchDescription.version;
        if (versionFormat != null) {
            updateParentVersion(projectModel, versionFormat);
            updateVersion(projectModel, versionFormat);
            logger.info("project version: " + GAV.of(projectModel).getVersion());

            updateDependencyVersions(projectModel, versionFormat);
            updatePluginVersions(projectModel, versionFormat);
        }

        final Map<String, String> propertyFormats = patchDescription.properties;
        if (propertyFormats != null && !propertyFormats.isEmpty()) {
            updatePropertyValues(projectModel, propertyFormats, originalProjectGAV);
        }

        // profile section
        updateProfiles(projectModel, patchDescription, originalProjectGAV);

        addGitProperties(projectModel);
    }


    private void updateProfiles(Model model, RefPatchDescription patchDescription, GAV originalProjectGAV) {
        List<Profile> profiles = model.getProfiles();

        // profile section
        if (!profiles.isEmpty()) {
            for (Profile profile : profiles) {
                String version = patchDescription.version;
                if (version != null) {
                    updateDependencyVersions(profile, version);
                    updatePluginVersions(profile, version);
                }

                Map<String, String> propertyFormats = patchDescription.properties;
                if (propertyFormats != null && !propertyFormats.isEmpty()) {
                    updatePropertyValues(profile, propertyFormats, originalProjectGAV);
                }
            }
        }
    }

    private void updateParentVersion(Model projectModel, String versionFormat) {
        Parent parent = projectModel.getParent();
        if (parent != null) {
            GAV parentGAV = GAV.of(parent);
            if (isRelatedProject(parentGAV)) {
                String gitVersion = getGitVersion(versionFormat, parentGAV);
                logger.debug("set parent version to " + gitVersion + " (" + parentGAV + ")");
                parent.setVersion(gitVersion);
            }
        }
    }

    private void updateVersion(Model projectModel, String versionFormat) {
        if (projectModel.getVersion() != null) {
            GAV projectGAV = GAV.of(projectModel);
            String gitVersion = getGitVersion(versionFormat, projectGAV);
            logger.debug("set version to " + gitVersion);
            projectModel.setVersion(gitVersion);
        }
    }

    private void updatePropertyValues(ModelBase model, Map<String, String> propertyFormats, GAV originalProjectGAV) {

        boolean logHeader = true;
        // properties section
        for (Entry<Object, Object> modelProperty : model.getProperties().entrySet()) {
            String modelPropertyName = (String) modelProperty.getKey();
            String modelPropertyValue = (String) modelProperty.getValue();

            String propertyFormat = propertyFormats.get(modelPropertyName);
            if (propertyFormat != null) {
                String gitPropertyValue = getGitPropertyValue(propertyFormat, modelPropertyValue, originalProjectGAV);
                if (!gitPropertyValue.equals(modelPropertyValue)) {
                    if (logHeader) {
                        logger.info(sectionLogHeader("properties", model));
                        logHeader = false;
                    }
                    logger.info("set property " + modelPropertyName + " to " + gitPropertyValue);
                    model.addProperty(modelPropertyName, gitPropertyValue);
                }
            }
        }
    }

    private void updatePluginVersions(ModelBase model, String versionFormat) {
        BuildBase build = getBuild(model);
        if (build == null) {
            return;
        }
        // plugins section
        {
            List<Plugin> relatedPlugins = filterRelatedPlugins(build.getPlugins());
            if (!relatedPlugins.isEmpty()) {
                logger.debug(sectionLogHeader("plugins", model));
                for (Plugin plugin : relatedPlugins) {
                    updateVersion(plugin, versionFormat);
                }
            }
        }

        // plugin management section
        PluginManagement pluginManagement = build.getPluginManagement();
        if (pluginManagement != null) {
            List<Plugin> relatedPlugins = filterRelatedPlugins(pluginManagement.getPlugins());
            if (!relatedPlugins.isEmpty()) {
                logger.debug(sectionLogHeader("plugin management", model));
                for (Plugin plugin : relatedPlugins) {
                    updateVersion(plugin, versionFormat);
                }
            }
        }

        // reporting section
        Reporting reporting = model.getReporting();
        if (reporting != null) {
            List<ReportPlugin> relatedPlugins = filterRelatedReportPlugins(reporting.getPlugins());
            if (!relatedPlugins.isEmpty()) {
                logger.debug(sectionLogHeader("reporting plugins", model));
                for (ReportPlugin plugin : relatedPlugins) {
                    updateVersion(plugin, versionFormat);
                }
            }
        }
    }

    private void updateVersion(Plugin plugin, String versionFormat) {
        if (plugin.getVersion() != null) {
            GAV pluginGAV = GAV.of(plugin);
            String gitVersion = getGitVersion(versionFormat, pluginGAV);
            logger.debug(pluginGAV.getProjectId() + ": set version to " + gitVersion);
            plugin.setVersion(gitVersion);
        }
    }

    private void updateVersion(ReportPlugin plugin, String versionFormat) {
        if (plugin.getVersion() != null) {
            GAV pluginGAV = GAV.of(plugin);
            String gitVersion = getGitVersion(versionFormat, pluginGAV);
            logger.debug(pluginGAV.getProjectId() + ": set version to " + gitVersion);
            plugin.setVersion(gitVersion);
        }
    }

    private List<Plugin> filterRelatedPlugins(List<Plugin> plugins) {
        return plugins.stream()
                .filter(it -> isRelatedProject(GAV.of(it)))
                .collect(toList());
    }

    private List<ReportPlugin> filterRelatedReportPlugins(List<ReportPlugin> plugins) {
        return plugins.stream()
                .filter(it -> isRelatedProject(GAV.of(it)))
                .collect(toList());
    }

    private void updateDependencyVersions(ModelBase model, String versionFormat) {
        // dependencies section
        {
            List<Dependency> relatedDependencies = filterRelatedDependencies(model.getDependencies());
            if (!relatedDependencies.isEmpty()) {
                logger.debug(sectionLogHeader("dependencies", model));
                for (Dependency dependency : relatedDependencies) {
                    updateVersion(dependency, versionFormat);
                }
            }
        }
        // dependency management section
        DependencyManagement dependencyManagement = model.getDependencyManagement();
        if (dependencyManagement != null) {
            List<Dependency> relatedDependencies = filterRelatedDependencies(dependencyManagement.getDependencies());
            if (!relatedDependencies.isEmpty()) {
                logger.debug(sectionLogHeader("dependency management", model));
                for (Dependency dependency : relatedDependencies) {
                    updateVersion(dependency, versionFormat);
                }
            }
        }
    }

    private void updateVersion(Dependency dependency, String versionFormat) {
        if (dependency.getVersion() != null) {
            GAV dependencyGAV = GAV.of(dependency);
            String gitVersion = getGitVersion(versionFormat, dependencyGAV);
            logger.debug(dependencyGAV.getProjectId() + ": set version to " + gitVersion);
            dependency.setVersion(gitVersion);
        }
    }

    public List<Dependency> filterRelatedDependencies(List<Dependency> dependencies) {
        return dependencies.stream()
                .filter(it -> isRelatedProject(GAV.of(it)))
                .collect(toList());
    }

    private void addGitProperties(Model projectModel) {
        gitProjectProperties.forEach(projectModel::addProperty);
    }

    private void addBuildPlugin(Model projectModel) {
        logger.debug("add version build plugin");

        Plugin plugin = asPlugin();

        PluginExecution execution = new PluginExecution();
        execution.setId(GitVersioningMojo.GOAL);
        execution.getGoals().add(GitVersioningMojo.GOAL);

        plugin.getExecutions().add(execution);

        if (projectModel.getBuild() == null) {
            projectModel.setBuild(new Build());
        }
        // add at index 0 to be executed before any other project plugin,
        // to prevent malfunctions with other plugins
        projectModel.getBuild().getPlugins().add(0, plugin);
    }


    // ---- versioning -------------------------------------------------------------------------------------------------



    private String getGitVersion(String versionFormat, GAV originalProjectGAV) {
        final Map<String, Supplier<String>> placeholderMap = generateFormatPlaceholderMap(originalProjectGAV);

        return slugify(substituteText(versionFormat, placeholderMap));
    }

    private String getGitPropertyValue(String propertyFormat, String originalValue, GAV originalProjectGAV) {
        final Map<String, Supplier<String>> placeholderMap = generateFormatPlaceholderMap(originalProjectGAV);
        placeholderMap.put("value", () -> originalValue);
        return substituteText(propertyFormat, placeholderMap);
    }

    private Map<String, Supplier<String>> generateFormatPlaceholderMap(GAV originalProjectGAV) {
        final Map<String, Supplier<String>> placeholderMap = new HashMap<>(globalFormatPlaceholderMap);
        final Supplier<String> originalProjectVersion = originalProjectGAV::getVersion;
        placeholderMap.put("version", originalProjectVersion);
        placeholderMap.put("version.release", Lazy.by(() -> originalProjectVersion.get().replaceFirst("-SNAPSHOT$", "")));

        String[] versionComponents = originalProjectVersion.get().replaceFirst("-.*$","").split("\\.");
        placeholderMap.put("version.major", Lazy.by(() -> versionComponents.length > 0 ? versionComponents[0] : ""));
        placeholderMap.put("version.minor", Lazy.by(() -> versionComponents.length > 1 ? versionComponents[1] : ""));
        placeholderMap.put("version.patch", Lazy.by(() -> versionComponents.length > 1 ? versionComponents[2] : ""));

        return placeholderMap;
    }

    private static Map<String, Supplier<String>> generateGlobalFormatPlaceholderMap(ContextProvider contextProvider) throws IOException {
        final MavenSession mavenSession = contextProvider.getMavenSession();
        final GitSituation gitSituation = contextProvider.getGitSituation();
        final RefPatchMatch patchMatch = contextProvider.getPatchMatch();

        final Map<String, Supplier<String>> placeholderMap = new HashMap<>();

        final Lazy<String> hash = Lazy.by(gitSituation::getRev);
        placeholderMap.put("commit", hash);
        placeholderMap.put("commit.short", Lazy.by(() -> hash.get().substring(0, 7)));

        final Lazy<ZonedDateTime> headCommitDateTime = Lazy.by(gitSituation::getTimestamp);
        placeholderMap.put("commit.timestamp", Lazy.by(() -> String.valueOf(headCommitDateTime.get().toEpochSecond())));
        placeholderMap.put("commit.timestamp.year", Lazy.by(() -> String.valueOf(headCommitDateTime.get().getYear())));
        placeholderMap.put("commit.timestamp.year.2digit", Lazy.by(() -> String.valueOf(headCommitDateTime.get().getYear() % 100)));
        placeholderMap.put("commit.timestamp.month", Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getMonthValue()), 2, "0")));
        placeholderMap.put("commit.timestamp.day", Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getDayOfMonth()), 2, "0")));
        placeholderMap.put("commit.timestamp.hour", Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getHour()), 2, "0")));
        placeholderMap.put("commit.timestamp.minute", Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getMinute()), 2, "0")));
        placeholderMap.put("commit.timestamp.second", Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getSecond()), 2, "0")));
        placeholderMap.put("commit.timestamp.datetime", Lazy.by(() -> headCommitDateTime.get().toEpochSecond() > 0
                ? headCommitDateTime.get().format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")) : "00000000.000000"));

        final String refName = patchMatch.getRefName();
        final Lazy<String> refNameSlug = Lazy.by(() -> slugify(refName));
        placeholderMap.put("ref", () -> refName);
        placeholderMap.put("ref" + ".slug", refNameSlug);

        final Pattern refPattern = patchMatch.getPatchDescription().pattern;
        if (refPattern != null) {
            // ref pattern groups
            for (Entry<String, String> patternGroup : patternGroupValues(refPattern, refName).entrySet()) {
                final String groupName = patternGroup.getKey();
                final String value = patternGroup.getValue() != null ? patternGroup.getValue() : "";
                placeholderMap.put("ref." + groupName, () -> value);
                placeholderMap.put("ref." + groupName + ".slug", Lazy.by(() -> slugify(value)));
            }
        }

        // dirty
        final Lazy<Boolean> dirty = Lazy.by(() -> !gitSituation.isClean());
        placeholderMap.put("dirty", Lazy.by(() -> dirty.get() ? "-DIRTY" : ""));
        placeholderMap.put("dirty.snapshot", Lazy.by(() -> dirty.get() ? "-SNAPSHOT" : ""));

        // describe
        final Lazy<GitDescription> description = Lazy.by(gitSituation::getDescription);
        placeholderMap.put("describe", Lazy.by(() -> description.get().toString()));
        final Lazy<String> descriptionTag = Lazy.by(() -> description.get().getTag());
        placeholderMap.put("describe.tag", descriptionTag);
        placeholderMap.put("describe.distance", Lazy.by(() -> String.valueOf(description.get().getDistance())));

        // describe tag pattern groups
        final Lazy<Map<String, String>> describeTagPatternValues = Lazy.by(
                () -> patternGroupValues(gitSituation.getDescribeTagPattern(), descriptionTag.get()));
        for (String groupName : patternGroups(gitSituation.getDescribeTagPattern())) {
            Lazy<String> value = Lazy.by(() -> describeTagPatternValues.get().get(groupName));
            placeholderMap.put("describe.tag." + groupName, value);
            placeholderMap.put("describe.tag." + groupName + ".slug", Lazy.by(() -> slugify(value.get())));
        }

        // command parameters e.g. mvn -Dfoo=123 will be available as ${property.foo}
        for (Entry<Object, Object> property : mavenSession.getUserProperties().entrySet()) {
            if (property.getValue() != null) {
                placeholderMap.put("property." + property.getKey(), () -> property.getValue().toString());
            }
        }

        // environment variables e.g. BUILD_NUMBER=123 will be available as ${env.BUILD_NUMBER}
        System.getenv().forEach((key, value) -> placeholderMap.put("env." + key, () -> value));

        return placeholderMap;
    }

    private static Map<String, String> generateGitProjectProperties(ContextProvider contextProvider) throws IOException {
        final GitSituation gitSituation = contextProvider.getGitSituation();
        final RefPatchMatch patchMatch = contextProvider.getPatchMatch();

        final Map<String, String> properties = new HashMap<>();

        properties.put("git.worktree", gitSituation.getRootDirectory().getAbsolutePath());

        properties.put("git.commit", patchMatch.getCommit());
        properties.put("git.commit.short", patchMatch.getCommit().substring(0, 7));

        final ZonedDateTime headCommitDateTime = gitSituation.getTimestamp();
        properties.put("git.commit.timestamp", String.valueOf(headCommitDateTime.toEpochSecond()));
        properties.put("git.commit.timestamp.datetime", headCommitDateTime.toEpochSecond() > 0
                ? headCommitDateTime.format(ISO_INSTANT) : "0000-00-00T00:00:00Z");

        final String refName = patchMatch.getRefName();
        final String refNameSlug = slugify(refName);
        properties.put("git.ref", refName);
        properties.put("git.ref" + ".slug", refNameSlug);

        return properties;
    }


    // ---- determine related projects ---------------------------------------------------------------------------------

    private Set<GAV> determineRelatedProjects(Model projectModel) throws IOException {
        final HashSet<GAV> relatedProjects = new HashSet<>();
        determineRelatedProjects(projectModel, relatedProjects);
        contextProvider.getConfiguration().relatedProjects.stream()
                .map(it -> new GAV(it.groupId, it.artifactId, "*"))
                .forEach(relatedProjects::add);
        return relatedProjects;
    }

    private void determineRelatedProjects(Model projectModel, Set<GAV> relatedProjects) throws IOException {
        final GAV projectGAV = GAV.of(projectModel);
        if (relatedProjects.contains(projectGAV)) {
            return;
        }

        // add self
        relatedProjects.add(projectGAV);

        // check for related parent project by parent tag
        if (projectModel.getParent() != null) {
            final GAV parentGAV = GAV.of(projectModel.getParent());
            final File parentProjectPomFile = getParentProjectPomFile(projectModel);
            if (isRelatedPom(parentProjectPomFile)) {
                final Model parentProjectModel = readModel(parentProjectPomFile);
                final GAV parentProjectGAV = GAV.of(parentProjectModel);
                if (parentProjectGAV.equals(parentGAV)) {
                    determineRelatedProjects(parentProjectModel, relatedProjects);
                }
            }
        }

        // check for related parent project within parent directory
        final Model parentProjectModel = searchParentProjectInParentDirectory(projectModel);
        if (parentProjectModel != null) {
            determineRelatedProjects(parentProjectModel, relatedProjects);
        }

        //  process modules
        for (File modulePomFile : getProjectModules(projectModel)) {
            Model moduleProjectModel = readModel(modulePomFile);
            determineRelatedProjects(moduleProjectModel, relatedProjects);
        }
    }

    private boolean isRelatedProject(GAV project) {
        return relatedProjects.contains(project)
                || relatedProjects.contains(new GAV(project.getGroupId(), project.getArtifactId(), "*"));
    }


    /**
     * checks if <code>pomFile</code> is part of current maven and git context
     *
     * @param pomFile the pom file
     * @return true if <code>pomFile</code> is part of current maven and git context
     */
    private boolean isRelatedPom(File pomFile) throws IOException {
        return pomFile != null
                && pomFile.exists()
                && pomFile.isFile()
                // only project pom files ends in .xml, pom files from dependencies from repositories ends in .pom
                && pomFile.getName().endsWith(".xml")
                // TODO check if following condition is needed
                && pomFile.getCanonicalPath().startsWith(contextProvider.getMvnDirectory().getParentFile().getCanonicalPath() + File.separator)
                // only pom files within git directory are treated as project pom files
                && pomFile.getCanonicalPath().startsWith(contextProvider.getGitSituation().getRootDirectory().getCanonicalPath() + File.separator);
    }

    private Model searchParentProjectInParentDirectory(Model projectModel) throws IOException {
        // search for parent project by directory hierarchy
        File parentDirectoryPomFile = pomFile(projectModel.getProjectDirectory().getParentFile(), "pom.xml");
        if (parentDirectoryPomFile.exists() && isRelatedPom(parentDirectoryPomFile)) {
            // check if parent has module that points to current project directory
            Model parentDirectoryProjectModel = readModel(parentDirectoryPomFile);
            for (File modulePomFile : getProjectModules(parentDirectoryProjectModel)) {
                if (modulePomFile.getCanonicalFile().equals(projectModel.getPomFile().getCanonicalFile())) {
                    return parentDirectoryProjectModel;
                }
            }
        }
        return null;
    }

    private static File getParentProjectPomFile(Model projectModel) {
        if (projectModel.getParent() == null) {
            return null;
        }

        File parentProjectPomFile = pomFile(projectModel.getProjectDirectory(), projectModel.getParent().getRelativePath());
        if (parentProjectPomFile.exists()) {
            return parentProjectPomFile;
        }

        return null;
    }

    private static Set<File> getProjectModules(Model projectModel) {
        final Set<File> modules = new HashSet<>();

        // modules section
        for (String module : projectModel.getModules()) {
            modules.add(pomFile(projectModel.getProjectDirectory(), module));
        }

        // profiles section
        for (Profile profile : projectModel.getProfiles()) {

            // modules section
            for (String module : profile.getModules()) {
                modules.add(pomFile(projectModel.getProjectDirectory(), module));
            }
        }

        return modules.stream().filter(File::exists).collect(toSet());
    }


    // ---- generate git versioned pom file ----------------------------------------------------------------------------

    private File writePomFile(Model projectModel) throws IOException {
        File gitVersionedPomFile = new File(projectModel.getProjectDirectory(), ContextProvider.GIT_VERSIONING_POM_NAME);
        logger.debug("generate " + gitVersionedPomFile);

        // read original pom file
        Document gitVersionedPomDocument = readXml(projectModel.getPomFile());
        Element projectElement = gitVersionedPomDocument.getChild("project");

        // update project
        updateParentVersion(projectElement, projectModel.getParent());
        updateVersion(projectElement, projectModel);
        updatePropertyValues(projectElement, projectModel);
        updateDependencyVersions(projectElement, projectModel);
        updatePluginVersions(projectElement, projectModel.getBuild(), projectModel.getReporting());

        updateProfiles(projectElement, projectModel.getProfiles());

        writeXml(gitVersionedPomFile, gitVersionedPomDocument);

        return gitVersionedPomFile;
    }

    private static void updateParentVersion(Element projectElement, Parent parent) {
        Element parentElement = projectElement.getChild("parent");
        if (parentElement != null) {
            Element parentVersionElement = parentElement.getChild("version");
            parentVersionElement.setText(parent.getVersion());
        }
    }

    private static void updateVersion(Element projectElement, Model projectModel) {
        Element versionElement = projectElement.getChild("version");
        if (versionElement != null) {
            versionElement.setText(projectModel.getVersion());
        }
    }

    private void updatePropertyValues(Element element, ModelBase model) throws IOException {
        // properties section
        Element propertiesElement = element.getChild("properties");
        if (propertiesElement != null) {
            Properties modelProperties = model.getProperties();
            contextProvider.getPatchMatch().getPatchDescription().properties.keySet().forEach(propertyName -> {
                Element propertyElement = propertiesElement.getChild(propertyName);
                if (propertyElement != null) {
                    String pomPropertyValue = propertyElement.getText();
                    String modelPropertyValue = (String) modelProperties.get(propertyName);
                    if (!Objects.equals(modelPropertyValue, pomPropertyValue)) {
                        propertyElement.setText(modelPropertyValue);
                    }
                }
            });
        }
    }

    private static void updateDependencyVersions(Element element, ModelBase model) {
        // dependencies section
        {
            Element dependenciesElement = element.getChild("dependencies");
            if (dependenciesElement != null) {
                updateDependencyVersions(dependenciesElement, model.getDependencies());
            }
        }
        // dependencyManagement section
        Element dependencyManagementElement = element.getChild("dependencyManagement");
        if (dependencyManagementElement != null) {
            Element dependenciesElement = dependencyManagementElement.getChild("dependencies");
            if (dependenciesElement != null) {
                updateDependencyVersions(dependenciesElement, model.getDependencyManagement().getDependencies());
            }
        }
    }

    private static void updateDependencyVersions(Element dependenciesElement, List<Dependency> dependencies) {
        forEachPair(dependenciesElement.getChildren(), dependencies, (dependencyElement, dependency) -> {
            // sanity check
            if (!Objects.equals(dependency.getManagementKey(), getDependencyManagementKey(dependencyElement))) {
                throw new IllegalArgumentException("Unexpected difference of xml and model dependencies order");
            }

            Element dependencyVersionElement = dependencyElement.getChild("version");
            if (dependencyVersionElement != null) {
                dependencyVersionElement.setText(dependency.getVersion());
            }
        });
    }

    private static String getDependencyManagementKey(Element element) {
        Element groupId = element.getChild("groupId");
        Element artifactId = element.getChild("artifactId");
        Element type = element.getChild("type");
        Element classifier = element.getChild("classifier");
        return (groupId != null ? groupId.getText().trim() : "")
                + ":" + (artifactId != null ? artifactId.getText().trim() : "")
                + ":" + (type != null ? type.getText().trim() : "jar")
                + (classifier != null ? ":" + classifier.getText().trim() : "");
    }

    private static void updatePluginVersions(Element projectElement, BuildBase build, Reporting reporting) {
        // build section
        Element buildElement = projectElement.getChild("build");
        if (buildElement != null) {
            // plugins section
            {
                Element pluginsElement = buildElement.getChild("plugins");
                if (pluginsElement != null) {
                    updatePluginVersions(pluginsElement, build.getPlugins());
                }
            }
            // pluginManagement section
            Element pluginsManagementElement = buildElement.getChild("pluginsManagement");
            if (pluginsManagementElement != null) {
                Element pluginsElement = pluginsManagementElement.getChild("plugins");
                if (pluginsElement != null) {
                    updatePluginVersions(pluginsElement, build.getPluginManagement().getPlugins());
                }
            }
        }

        Element reportingElement = projectElement.getChild("reporting");
        if (reportingElement != null) {
            // plugins section
            {
                Element pluginsElement = reportingElement.getChild("plugins");
                if (pluginsElement != null) {
                    updateReportPluginVersions(pluginsElement, reporting.getPlugins());
                }
            }
        }
    }

    private static void updatePluginVersions(Element pluginsElement, List<Plugin> plugins) {
        forEachPair(pluginsElement.getChildren(), plugins, (pluginElement, plugin) -> {
            // sanity check
            if (!Objects.equals(plugin.getKey(), getPluginKey(pluginElement))) {
                throw new IllegalArgumentException("Unexpected difference of xml and model plugin order");
            }

            Element pluginVersionElement = pluginElement.getChild("version");
            if (pluginVersionElement != null) {
                pluginVersionElement.setText(plugin.getVersion());
            }
        });
    }

    private static void updateReportPluginVersions(Element pluginsElement, List<ReportPlugin> plugins) {
        forEachPair(pluginsElement.getChildren(), plugins, (pluginElement, plugin) -> {
            // sanity check
            if (!Objects.equals(plugin.getKey(), getPluginKey(pluginElement))) {
                throw new IllegalArgumentException("Unexpected difference of xml and model plugin order");
            }

            Element pluginVersionElement = pluginElement.getChild("version");
            if (pluginVersionElement != null) {
                pluginVersionElement.setText(plugin.getVersion());
            }
        });
    }

    private static String getPluginKey(Element element) {
        Element groupId = element.getChild("groupId");
        Element artifactId = element.getChild("artifactId");
        return (groupId != null ? groupId.getText().trim() : "org.apache.maven.plugins")
                + ":" + (artifactId != null ? artifactId.getText().trim() : "");
    }

    private void updateProfiles(Element projectElement, List<Profile> profiles) throws IOException {
        Element profilesElement = projectElement.getChild("profiles");
        if (profilesElement != null) {
            Map<String, Profile> profileMap = profiles.stream()
                    .collect(toMap(Profile::getId, it -> it));
            for (Element profileElement : profilesElement.getChildren("profile")) {
                Profile profile = profileMap.get(profileElement.getChild("id").getText());
                updatePropertyValues(profileElement, profile);
                updateDependencyVersions(profileElement, profile);
                updatePluginVersions(profileElement, profile.getBuild(), profile.getReporting());
            }
        }
    }


    // ---- misc -------------------------------------------------------------------------------------------------------

    private static String extensionLogHeader(GAV extensionGAV) {
        String extension = extensionGAV.toString();
        String metaInfo = "[core extension]";

        String plainLog = extension + " " + metaInfo;
        String formattedLog = buffer()
                .a(" ").mojo(extension).a(" ").strong(metaInfo).a(" ")
                .toString();

        return padLogHeaderPadding(plainLog, formattedLog);
    }

    private static String padLogHeaderPadding(String plainLog, String formattedLog) {
        String pad = "-";
        int padding = max(6, 72 - 2 - plainLog.length());
        int paddingLeft = (int) floor(padding / 2.0);
        int paddingRight = (int) ceil(padding / 2.0);
        return buffer()
                .strong(repeat(pad, paddingLeft))
                .a(formattedLog)
                .strong(repeat(pad, paddingRight))
                .toString();
    }

    private static String projectLogHeader(GAV projectGAV) {
        String project = projectGAV.getProjectId();
        return buffer().project(project).toString();
    }

    private static String sectionLogHeader(String title, ModelBase model) {
        String header = title + ":";
        if (model instanceof Profile) {
            header = buffer().strong("profile " + ((Profile) model).getId() + " ") + header;
        }
        return header;
    }

    private static String slugify(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("/", "-");
    }


    // ---- utils ------------------------------------------------------------------------------------------------------

    public static <T1, T2> void forEachPair(Collection<T1> collection1, Collection<T2> collection2, BiConsumer<T1, T2> consumer) {
        if (collection1.size() != collection2.size()) {
            throw new IllegalArgumentException("Collections sizes are not equals");
        }

        Iterator<T1> iter1 = collection1.iterator();
        Iterator<T2> iter2 = collection2.iterator();
        while (iter1.hasNext() && iter2.hasNext()) {
            consumer.accept(iter1.next(), iter2.next());
        }
    }
}

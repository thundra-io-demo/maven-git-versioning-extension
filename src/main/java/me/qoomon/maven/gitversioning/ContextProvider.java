package me.qoomon.maven.gitversioning;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.google.inject.Key;
import com.google.inject.OutOfScopeException;
import me.qoomon.gitversioning.commons.GitSituation;
import me.qoomon.gitversioning.commons.Lazy;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.session.scope.internal.SessionScope;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS;
import static java.lang.Boolean.parseBoolean;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static me.qoomon.gitversioning.commons.GitRefType.*;
import static me.qoomon.gitversioning.commons.GitRefType.COMMIT;
import static me.qoomon.maven.gitversioning.BuildProperties.projectArtifactId;
import static org.slf4j.LoggerFactory.getLogger;

@Singleton
public class ContextProvider {

    private static final String OPTION_NAME_GIT_REF = "git.ref";
    private static final String OPTION_NAME_GIT_TAG = "git.tag";
    private static final String OPTION_NAME_GIT_BRANCH = "git.branch";
    private static final String OPTION_NAME_DISABLE = "versioning.disable";
    private static final String OPTION_UPDATE_POM = "versioning.updatePom";

    static final String GIT_VERSIONING_POM_NAME = ".git-versioned-pom.xml";

    final private Logger logger = getLogger(ContextProvider.class);

    @Inject
    private SessionScope sessionScope;

    private MavenSession mavenSession;  // can't be injected, because it's not available before model read
    private File executionRootDirectory;
    private File mvnDirectory;
    private Configuration instance;
    private GitSituation gitSituation;

    private RefPatchMatch patchMatch;

    public Configuration getConfiguration() throws IOException {
        if (instance == null) {
            final File configFile = new File(getMvnDirectory(), projectArtifactId() + ".xml");
            logger.debug("read config from " + configFile);
            instance = readConfig(configFile);
        }
        return instance;
    }

    public MavenSession getMavenSession() {
        if(mavenSession == null) {
            mavenSession = sessionScope.scope(Key.get(MavenSession.class), null).get();
        }
        return mavenSession;
    }

    public RefPatchMatch getPatchMatch() throws IOException {
        if (patchMatch == null) {
            patchMatch = getPatchMatch(getGitSituation(), getConfiguration());
        }
        return patchMatch;
    }

    public GitSituation getGitSituation() throws IOException {
        if(gitSituation == null) {
            final FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder().findGitDir(getExecutionRootDirectory());
            if (repositoryBuilder.getGitDir() == null) {
                throw new RuntimeException("maven execution root directory is not a git repository (or any of the parent directories): "
                        + getExecutionRootDirectory());
            }
            final Repository repository = repositoryBuilder.build();
            gitSituation = new GitSituation(repository) {
                {
                    String overrideBranch = getCommandOption(OPTION_NAME_GIT_BRANCH);
                    String overrideTag = getCommandOption(OPTION_NAME_GIT_TAG);

                    if (overrideBranch == null && overrideTag == null) {
                        final String providedRef = getCommandOption(OPTION_NAME_GIT_REF);
                        if (providedRef != null) {
                            if (!providedRef.startsWith("refs/")) {
                                throw new IllegalArgumentException("invalid provided ref " + providedRef + " -  needs to start with refs/");
                            }

                            if (providedRef.startsWith("refs/tags/")) {
                                overrideTag = providedRef;
                            } else {
                                overrideBranch = providedRef;
                            }
                        }
                    }

                    // GitHub Actions support
                    if (overrideBranch == null && overrideTag == null) {
                        final String githubEnv = System.getenv("GITHUB_ACTIONS");
                        if (githubEnv != null && githubEnv.equals("true")) {
                            logger.info("gather git situation from GitHub Actions environment variable: GITHUB_REF");
                            String githubRef = System.getenv("GITHUB_REF");
                            logger.debug("  GITHUB_REF: " + githubRef);
                            if (githubRef != null && githubRef.startsWith("refs/")) {
                                if (githubRef.startsWith("refs/tags/")) {
                                    overrideTag = githubRef;
                                } else {
                                    overrideBranch = githubRef;
                                }
                            }
                        }
                    }

                    // GitLab CI support
                    if (overrideBranch == null && overrideTag == null) {
                        final String gitlabEnv = System.getenv("GITLAB_CI");
                        if (gitlabEnv != null && gitlabEnv.equals("true")) {
                            logger.info("gather git situation from GitLab CI environment variables: CI_COMMIT_BRANCH and CI_COMMIT_TAG");
                            String commitBranch = System.getenv("CI_COMMIT_BRANCH");
                            String commitTag = System.getenv("CI_COMMIT_TAG");
                            logger.debug("  CI_COMMIT_BRANCH: " + commitBranch);
                            logger.debug("  CI_COMMIT_TAG: " + commitTag);
                            overrideBranch = commitBranch;
                            overrideTag = commitTag;
                        }
                    }

                    // Circle CI support
                    if (overrideBranch == null && overrideTag == null) {
                        final String circleciEnv = System.getenv("CIRCLECI");
                        if (circleciEnv != null && circleciEnv.equals("true")) {
                            logger.info("gather git situation from Circle CI environment variables: CIRCLE_BRANCH and CIRCLE_TAG");
                            String commitBranch = System.getenv("CIRCLE_BRANCH");
                            String commitTag = System.getenv("CIRCLE_TAG");
                            logger.debug("  CIRCLE_BRANCH: " + commitBranch);
                            logger.debug("  CIRCLE_TAG: " + commitTag);
                            overrideBranch = System.getenv("CIRCLE_BRANCH");
                            overrideTag = System.getenv("CIRCLE_TAG");
                        }
                    }

                    // Jenkins support
                    if (overrideBranch == null && overrideTag == null) {
                        final String jenkinsEnv = System.getenv("JENKINS_HOME");
                        if (jenkinsEnv != null && !jenkinsEnv.trim().isEmpty()) {
                            logger.info("gather git situation from jenkins environment variables: BRANCH_NAME and TAG_NAME");
                            String branchName = System.getenv("BRANCH_NAME");
                            String tagName = System.getenv("TAG_NAME");
                            logger.debug("  BRANCH_NAME: " + branchName);
                            logger.debug("  TAG_NAME: " + tagName);
                            if (branchName != null && branchName.equals(tagName)) {
                                overrideTag = tagName;
                            } else {
                                overrideBranch = branchName;
                                overrideTag = tagName;
                            }
                        }
                    }

                    if (overrideBranch != null || overrideTag != null) {
                        overrideBranch(overrideBranch);
                        overrideTags(overrideTag);
                    }
                }

                void overrideBranch(String branch) {
                    if (branch != null && branch.trim().isEmpty()) {
                        branch = null;
                    }

                    if (branch != null) {
                        if (branch.startsWith("refs/tags/")) {
                            throw new IllegalArgumentException("invalid branch ref" + branch);
                        }

                        // two replacement steps to support default branches (heads)
                        // and other refs e.g. GitHub pull requests refs/pull/1000/head
                        branch = branch.replaceFirst("^refs/", "")
                                .replaceFirst("^heads/", "");
                    }

                    logger.debug("override git branch with: " + branch);
                    setBranch(branch);
                }

                void overrideTags(String tag) {
                    if (tag != null && tag.trim().isEmpty()) {
                        tag = null;
                    }

                    if (tag != null) {
                        if (tag.startsWith("refs/") && !tag.startsWith("refs/tags/")) {
                            throw new IllegalArgumentException("invalid tag ref" + tag);
                        }

                        tag = tag.replaceFirst("^refs/tags/", "");
                    }

                    logger.debug("override git tags with: " + tag);
                    setTags(tag == null ? emptyList() : singletonList(tag));
                }
            };
            gitSituation.setDescribeTagPattern(getPatchMatch().getPatchDescription().describeTagPattern);
        }
        return gitSituation;
    }

    private String getCommandOption(final String name) {
        String value = getMavenSession().getUserProperties().getProperty(name);
        if (value == null) {
            String plainName = name.replaceFirst("^versioning\\.", "");
            String environmentVariableName = "VERSIONING_"
                    + String.join("_", plainName.split("(?=\\p{Lu})"))
                    .replaceAll("\\.", "_")
                    .toUpperCase();
            value = System.getenv(environmentVariableName);
        }
        if (value == null) {
            value = System.getProperty(name);
        }
        return value;
    }

    public boolean getDisableOption() throws IOException {
        try {
            mavenSession = sessionScope.scope(Key.get(MavenSession.class), null).get();
        } catch (OutOfScopeException ex) {
            logger.warn("versioning is disabled, because no maven session present");
            return true;
        }

        final String commandOptionDisable = getCommandOption(OPTION_NAME_DISABLE);
        if (commandOptionDisable != null) {
            boolean disabled = parseBoolean(commandOptionDisable);
            if (disabled) {
                logger.debug("versioning is disabled by command option");
                return true;
            }
        } else {
            // check if extension is disabled by config option
            boolean disabled = getConfiguration().disable != null && getConfiguration().disable;
            if (disabled) {
                logger.debug("versioning is disabled by config option");
                return true;
            }
        }

        return false;
    }

    public boolean getUpdatePomOption() throws IOException {
        final String updatePomCommandOption = getCommandOption(OPTION_UPDATE_POM);
        if (updatePomCommandOption != null) {
            return parseBoolean(updatePomCommandOption);
        }

        if (getPatchMatch().getPatchDescription().updatePom != null) {
            return getPatchMatch().getPatchDescription().updatePom;
        }

        return false;
    }

    private static Configuration readConfig(File configFile) throws IOException {
        final XmlMapper xmlMapper = new XmlMapper();
        xmlMapper.enable(ACCEPT_CASE_INSENSITIVE_ENUMS);

        final Configuration config = xmlMapper.readValue(configFile, Configuration.class);

        // consider global config
        List<Configuration.PatchDescription> patchDescriptions = new ArrayList<>(config.refs.list);
        if (config.rev != null) {
            patchDescriptions.add(config.rev);
        }
        for (Configuration.PatchDescription patchDescription : patchDescriptions) {
            if (patchDescription.describeTagPattern == null) {
                patchDescription.describeTagPattern = config.describeTagPattern;
            }
            if (patchDescription.updatePom == null) {
                patchDescription.updatePom = config.updatePom;
            }
        }

        return config;
    }

    public File getExecutionRootDirectory() {
        if(executionRootDirectory == null) {
            executionRootDirectory = new File(getMavenSession().getExecutionRootDirectory());
            logger.debug("execution root directory: " + executionRootDirectory);
        }
        return executionRootDirectory;
    }

    public File getMvnDirectory() throws IOException {
        if(mvnDirectory == null) {
            mvnDirectory = findMvnDirectory(getExecutionRootDirectory());
            logger.debug(".mvn directory: " + mvnDirectory);
        }
        return mvnDirectory;
    }
    private static File findMvnDirectory(File baseDirectory) throws IOException {
        File searchDirectory = baseDirectory;
        while (searchDirectory != null) {
            File mvnDir = new File(searchDirectory, ".mvn");
            if (mvnDir.exists()) {
                return mvnDir;
            }
            searchDirectory = searchDirectory.getParentFile();
        }

        throw new FileNotFoundException("Can not find .mvn directory in hierarchy of " + baseDirectory);
    }

    private static RefPatchMatch getPatchMatch(GitSituation gitSituation, Configuration config) {
        final Lazy<List<String>> sortedTags = Lazy.by(() -> gitSituation.getTags().stream()
                .sorted(comparing(DefaultArtifactVersion::new)).collect(toList()));
        for (Configuration.RefPatchDescription refConfig : config.refs.list) {
            switch (refConfig.type) {
                case TAG: {
                    if (gitSituation.isDetached() || config.refs.considerTagsOnBranches) {
                        for (String tag : sortedTags.get()) {
                            if (refConfig.pattern == null || refConfig.pattern.matcher(tag).matches()) {
                                return new RefPatchMatch(gitSituation.getRev(), tag, refConfig);
                            }
                        }
                    }
                }
                break;
                case BRANCH: {
                    if (!gitSituation.isDetached()) {
                        String branch = gitSituation.getBranch();
                        if (refConfig.pattern == null || refConfig.pattern.matcher(branch).matches()) {
                            return new RefPatchMatch(gitSituation.getRev(), branch, refConfig);
                        }
                    }
                }
                break;
                default:
                    throw new IllegalArgumentException("Unexpected ref type: " + refConfig.type);
            }
        }

        if (config.rev != null) {
            return new RefPatchMatch(gitSituation.getRev(), gitSituation.getRev(),
                    new Configuration.RefPatchDescription(COMMIT, null, config.rev));
        }

        return null;
    }
}

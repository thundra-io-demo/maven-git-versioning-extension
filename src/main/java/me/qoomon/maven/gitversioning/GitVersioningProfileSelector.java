package me.qoomon.maven.gitversioning;

import org.apache.maven.model.Profile;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.profile.DefaultProfileSelector;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.ProfileSelector;
import org.codehaus.plexus.component.annotations.Component;
import org.slf4j.Logger;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.*;
import static org.slf4j.LoggerFactory.getLogger;

@Named
@Singleton
@Component(role = ProfileSelector.class)
public class GitVersioningProfileSelector extends DefaultProfileSelector {

    final private Logger logger = getLogger(GitVersioningProfileSelector.class);

    @Inject
    private ContextProvider contextProvider;

    private Map<String, Profile> profileMap = new HashMap<>();



    @Override
    public List<Profile> getActiveProfiles(Collection<Profile> profiles, ProfileActivationContext context, ModelProblemCollector problems) {
        List<Profile> activeProfiles = super.getActiveProfiles(profiles, context, problems);
        if (context.getProjectDirectory() != null) {
            return activeProfiles;
        }

        try {
            System.err.println(problems.getClass().getDeclaredField("source").get(problems));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        System.err.println("getActiveProfiles: " + context.getProjectProperties());


        return activeProfiles;

//        final Set<String> activeProfileIds = activeProfiles.stream().map(Profile::getId).collect(toSet());
//
//        final Map<String, Boolean> desiredProfileStateMap;
//        try {
//            desiredProfileStateMap = contextProvider.getPatchMatch().getPatchDescription().profiles;
//        } catch (IOException ex) {
//            throw new RuntimeException(ex);
//        }
//
//        return profiles.stream().filter(profile -> {
//            logger.info("handle profile: " + profile.getId());
//            if (activeProfileIds.contains(profile.getId())) {
//                logger.info("handle inactive profile: " + profile.getId());
//                if (!desiredProfileStateMap.getOrDefault(profile.getId(), true)) {
//                    logger.info("deactivate profile: " + profile.getId());
//                    return false;
//                }
//                return true;
//            } else {
//                logger.info("handle inactive profile: " + profile.getId());
//                if (desiredProfileStateMap.getOrDefault(profile.getId(), false)) {
//                    logger.info("activate profile: " + profile.getId());
//                    return true;
//                }
//                return false;
//            }
//        }).collect(toList());
    }

    private Optional<Profile> findProfileById(Collection<Profile> profiles, String managedProfileId) {
        return profiles.stream()
                .filter(profile -> profile.getId().equals(managedProfileId)).findFirst();
    }
}

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins.ParameterizedRemoteTrigger;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.EnvironmentContributingAction;
import java.util.Map;

/**
 *
 * @author brownt
 */
public class RemoteBuildEnvInjectAction implements EnvironmentContributingAction{
    public static final String REMOTE_ENVINJECT_BUILDER_ACTION_NAME = "RemoteBuildEnvInjectAction";

    private transient Map<String, String> resultVariables;

    public RemoteBuildEnvInjectAction(Map<String, String> resultVariables) {
        this.resultVariables = resultVariables;
    }

    public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars envVars) {

        if (envVars == null) {
            return;
        }

        if (resultVariables == null) {
            return;
        }

        for (Map.Entry<String, String> entry : resultVariables.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && value != null) {
                envVars.put(key, value);
            }
        }
    }

    public String getIconFileName() {
        return null;
    }

    public String getDisplayName() {
        return REMOTE_ENVINJECT_BUILDER_ACTION_NAME;
    }

    public String getUrlName() {
        return null;
    }
}

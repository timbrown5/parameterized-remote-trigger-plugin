package org.jenkinsci.plugins.ParameterizedRemoteTrigger;

import hudson.AbortException;

import hudson.FilePath;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.CopyOnWriteList;
import hudson.util.ListBoxModel;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
//import net.sf.json.
//import net.sf.json.

import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.codec.binary.Base64;

/**
 * 
 * @author Maurice W.
 * 
 */
public class RemoteBuildConfiguration extends Builder {

    private final String          token;
    private final String          remoteJenkinsName;
    private final String          job;

    private final boolean         shouldNotFailBuild;
    private final int             pollInterval;
    private final int             connectionRetryLimit;
    private final boolean         preventRemoteBuildQueue;
    private final boolean         blockBuildUntilComplete;

    // "parameters" is the raw string entered by the user
    private final String          parameters;
    // "parameterList" is the cleaned-up version of "parameters" (stripped out comments, character encoding, etc)

    private final List<String>    parameterList;

    private static String         paramerizedBuildUrl = "/buildWithParameters";
    private static String         normalBuildUrl      = "/build";
    private static String         buildTokenRootUrl   = "/buildByToken";

    private final boolean         overrideAuth;
    private CopyOnWriteList<Auth> auth                = new CopyOnWriteList<Auth>();

    private final boolean         loadParamsFromFile;
    private String                parameterFile       = "";

    private String                queryString         = "";

    @DataBoundConstructor
    public RemoteBuildConfiguration(String remoteJenkinsName, boolean shouldNotFailBuild, String job, String token,
            String parameters, JSONObject overrideAuth, JSONObject loadParamsFromFile, boolean preventRemoteBuildQueue,
            boolean blockBuildUntilComplete, int pollInterval) throws MalformedURLException {

        this.token = token.trim();
        this.remoteJenkinsName = remoteJenkinsName;
        this.parameters = parameters;
        this.job = job.trim();
        this.shouldNotFailBuild = shouldNotFailBuild;
        this.preventRemoteBuildQueue = preventRemoteBuildQueue;
        this.blockBuildUntilComplete = blockBuildUntilComplete;
        this.pollInterval = pollInterval;
        this.connectionRetryLimit = 5;

        if (overrideAuth != null && overrideAuth.has("auth")) {
            this.overrideAuth = true;
            this.auth.replaceBy(new Auth(overrideAuth.getJSONObject("auth")));
        } else {
            this.overrideAuth = false;
            this.auth.replaceBy(new Auth(new JSONObject()));
        }

        if (loadParamsFromFile != null && loadParamsFromFile.has("parameterFile")) {
            this.loadParamsFromFile = true;
            this.parameterFile = loadParamsFromFile.getString("parameterFile");
        } else {
            this.loadParamsFromFile = false;
        }

        // TODO: clean this up a bit
        // split the parameter-string into an array based on the new-line character
        String[] params = parameters.split("\n");

        // convert the String array into a List of Strings, and remove any empty entries
        this.parameterList = new ArrayList<String>(Arrays.asList(params));

    }

    public RemoteBuildConfiguration(String remoteJenkinsName, boolean shouldNotFailBuild,
            boolean preventRemoteBuildQueue, boolean blockBuildUntilComplete, int pollInterval, String job,
            String token, String parameters) throws MalformedURLException {

        this.token = token.trim();
        this.remoteJenkinsName = remoteJenkinsName;
        this.parameters = parameters;
        this.job = job.trim();
        this.shouldNotFailBuild = shouldNotFailBuild;
        this.preventRemoteBuildQueue = preventRemoteBuildQueue;
        this.blockBuildUntilComplete = blockBuildUntilComplete;
        this.pollInterval = pollInterval;
        this.overrideAuth = false;
        this.auth.replaceBy(new Auth(null));
        this.connectionRetryLimit = 5;
        
        this.loadParamsFromFile = false;

        // split the parameter-string into an array based on the new-line character
        String[] params = parameters.split("\n");

        // convert the String array into a List of Strings, and remove any empty entries
        this.parameterList = new ArrayList<String>(Arrays.asList(params));

    }

    /**
     * Reads a file from the jobs workspace, and loads the list of parameters from with in it. It will also call
     * ```getCleanedParameters``` before returning.
     * 
     * @param build
     * @return List<String> of build parameters
     */
    private List<String> loadExternalParameterFile(AbstractBuild<?, ?> build) {

        FilePath workspace = build.getWorkspace();
        BufferedReader br = null;
        List<String> ParameterList = new ArrayList<String>();
        try {

            String filePath = workspace + this.getParameterFile();
            String sCurrentLine;
            String fileContent = "";

            br = new BufferedReader(new FileReader(filePath));

            while ((sCurrentLine = br.readLine()) != null) {
                // fileContent += sCurrentLine;
                ParameterList.add(sCurrentLine);
            }

            // ParameterList = new ArrayList<String>(Arrays.asList(fileContent));

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        // FilePath.
        return getCleanedParameters(ParameterList);
    }

    /**
     * Strip out any empty strings from the parameterList
     */
    private void removeEmptyElements(Collection<String> collection) {
        collection.removeAll(Arrays.asList(null, ""));
        collection.removeAll(Arrays.asList(null, " "));
    }

    /**
     * Convenience method
     * 
     * @return List<String> of build parameters
     */
    private List<String> getCleanedParameters() {

        return getCleanedParameters(this.getParameterList());
    }

    /**
     * Same as "getParameterList", but removes comments and empty strings Notice that no type of character encoding is
     * happening at this step. All encoding happens in the "buildUrlQueryString" method.
     * 
     * @param List
     *            <String> parameters
     * @return List<String> of build parameters
     */
    private List<String> getCleanedParameters(List<String> parameters) {
        List<String> params = new ArrayList<String>(parameters);
        removeEmptyElements(params);
        removeCommentsFromParameters(params);
        return params;
    }

    /**
     * Similar to "replaceToken", but acts on a list in place of just a single string
     * 
     * @param build
     * @param listener
     * @param params
     *            List<String> of params to be tokenized/replaced
     * @return List<String> of resolved variables/tokens
     */
    private List<String> replaceTokens(AbstractBuild<?, ?> build, BuildListener listener, List<String> params) {
        List<String> tokenizedParams = new ArrayList<String>();

        for (int i = 0; i < params.size(); i++) {
            tokenizedParams.add(replaceToken(build, listener, params.get(i)));
            // params.set(i, replaceToken(build, listener, params.get(i)));
        }

        return tokenizedParams;
    }

    /**
     * Resolves any environment variables in the string
     * 
     * @param build
     * @param listener
     * @param input
     *            String to be tokenized/replaced
     * @return String with resolved Environment variables
     */
    private String replaceToken(AbstractBuild<?, ?> build, BuildListener listener, String input) {
        try {
            return TokenMacro.expandAll(build, listener, input);
        } catch (Exception e) {
            listener.getLogger().println(
                    String.format("Failed to resolve parameters in string %s due to following error:\n%s", input,
                            e.getMessage()));
        }
        return input;
    }

    /**
     * Strip out any comments (lines that start with a #) from the collection that is passed in.
     */
    private void removeCommentsFromParameters(Collection<String> collection) {
        List<String> itemsToRemove = new ArrayList<String>();

        for (String parameter : collection) {
            if (parameter.indexOf("#") == 0) {
                itemsToRemove.add(parameter);
            }
        }
        collection.removeAll(itemsToRemove);
    }

    /**
     * Return the Collection<String> in an encoded query-string
     * 
     * @return query-parameter-formated URL-encoded string
     * @throws InterruptedException
     * @throws IOException
     * @throws MacroEvaluationException
     */
    private String buildUrlQueryString(Collection<String> parameters) {

        // List to hold the encoded parameters
        List<String> encodedParameters = new ArrayList<String>();

        for (String parameter : parameters) {

            // Step #1 - break apart the parameter-pairs (because we don't want to encode the "=" character)
            String[] splitParameters = parameter.split("=");

            // List to hold each individually encoded parameter item
            List<String> encodedItems = new ArrayList<String>();
            for (String item : splitParameters) {
                try {
                    // Step #2 - encode each individual parameter item add the encoded item to its corresponding list

                    encodedItems.add(encodeValue(item));

                } catch (Exception e) {
                    // do nothing
                    // because we are "hard-coding" the encoding type, there is a 0% chance that this will fail.
                }

            }

            // Step #3 - reunite the previously separated parameter items and add them to the corresponding list
            encodedParameters.add(StringUtils.join(encodedItems, "="));
        }

        return StringUtils.join(encodedParameters, "&");
    }

    /**
     * Lookup up a Remote Jenkins Server based on display name
     * 
     * @param displayName
     *            Name of the configuration you are looking for
     * @return A RemoteSitez object
     */
    public RemoteJenkinsServer findRemoteHost(String displayName) {
        RemoteJenkinsServer match = null;

        for (RemoteJenkinsServer host : this.getDescriptor().remoteSites) {
            // if we find a match, then stop looping
            if (displayName.equals(host.getDisplayName())) {
                match = host;
                break;
            }
        }

        return match;
    }

    /**
     * Helper function to allow values to be added to the query string from any method.
     * 
     * @param item
     */
    private void addToQueryString(String item) {
        String currentQueryString = this.getQueryString();
        String newQueryString = "";

        if (currentQueryString == null || currentQueryString.equals("")) {
            newQueryString = item;
        } else {
            newQueryString = currentQueryString + "&" + item;
        }
        this.setQueryString(newQueryString);
    }

    /**
     * Build the proper URL to trigger the remote build
     * 
     * All passed in string have already had their tokens replaced with real values. All 'params' also have the proper
     * character encoding
     * 
     * @param job
     *            Name of the remote job
     * @param securityToken
     *            Security token used to trigger remote job
     * @param params
     *            Parameters for the remote job
     * @return fully formed, fully qualified remote trigger URL
     */
    private String buildTriggerUrl(String job, String securityToken, Collection<String> params) {
        RemoteJenkinsServer remoteServer = this.findRemoteHost(this.getRemoteJenkinsName());
        String triggerUrlString = remoteServer.getAddress().toString();

        // start building the proper URL based on known capabiltiies of the remote server
        if (remoteServer.getHasBuildTokenRootSupport()) {
            triggerUrlString += buildTokenRootUrl;
            triggerUrlString += getBuildTypeUrl();

            this.addToQueryString("job=" + this.encodeValue(job));

        } else {
            triggerUrlString += "/job/";
            triggerUrlString += this.encodeValue(job);
            triggerUrlString += getBuildTypeUrl();
        }

        // don't try to include a security token in the URL if none is provided
        if (!securityToken.equals("")) {
            this.addToQueryString("token=" + encodeValue(securityToken));
        }

        // turn our Collection into a query string
        String buildParams = buildUrlQueryString(params);

        if (!buildParams.isEmpty()) {
            this.addToQueryString(buildParams);
        }

        // by adding "delay=0", this will (theoretically) force this job to the top of the remote queue
        this.addToQueryString("delay=0");

        triggerUrlString += "?" + this.getQueryString();

        return triggerUrlString;
    }

    /**
     * Build the proper URL for GET calls
     * 
     * All passed in string have already had their tokens replaced with real values.
     * 
     * @param job
     *            Name of the remote job
     * @param securityToken
     *            Security token used to trigger remote job
     * @return fully formed, fully qualified remote trigger URL
     */
    private String buildGetUrl(String job, String securityToken) {

        RemoteJenkinsServer remoteServer = this.findRemoteHost(this.getRemoteJenkinsName());
        String urlString = remoteServer.getAddress().toString();

        urlString += "/job/";
        urlString += this.encodeValue(job);

        // don't try to include a security token in the URL if none is provided
        if (!securityToken.equals("")) {
            this.addToQueryString("token=" + encodeValue(securityToken));
        }
        return urlString;
    }

    /**
     * Convenience function to mark the build as failed. It's intended to only be called from this.perform();
     * 
     * @param e
     *            Exception that caused the build to fail
     * @param listener
     *            Build Listener
     * @throws IOException
     */
    private void failBuild(Exception e, BuildListener listener) throws IOException {
        e.getStackTrace();
        if (this.getShouldNotFailBuild()) {
            listener.error("Remote build failed for the following reason, but the build will continue:");
            listener.error(e.getMessage());
        } else {
            listener.error("Remote build failed for the following reason:");
            throw new AbortException(e.getMessage());
        }
    }
    
  
    /**
     * Performs the trigger remote job action. This jobs sets the following Environment Variables (the names are copied from Parameterized Trigger Plugin (see getEnvVarsMap).
     * @param build
     * @param launcher
     * @param listener
     * @return
     * @throws InterruptedException
     * @throws IOException
     * @throws IllegalArgumentException 
     */
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException,
            IOException, IllegalArgumentException {

        RemoteJenkinsServer remoteServer = this.findRemoteHost(this.getRemoteJenkinsName());

        // Stores the status of the remote build
        String buildStatusStr = "UNKNOWN";

        if (remoteServer == null) {
            this.failBuild(new Exception("No remote host is defined for this job."), listener);
            return true;
        }
        String remoteServerURL = remoteServer.getAddress().toString();
        List<String> cleanedParams = null;

        if (this.loadParamsFromFile) {
            cleanedParams = loadExternalParameterFile(build);
        } else {
            // tokenize all variables and encode all variables, then build the fully-qualified trigger URL
            cleanedParams = getCleanedParameters();
            cleanedParams = replaceTokens(build, listener, cleanedParams);
        }

        String jobName = replaceToken(build, listener, this.getJob());

        String securityToken = replaceToken(build, listener, this.getToken());

        String triggerUrlString = this.buildTriggerUrl(jobName, securityToken, cleanedParams);

        // Trigger remote job
        // print out some debugging information to the console

        //listener.getLogger().println("URL: " + triggerUrlString);
        listener.getLogger().println("Triggering this remote job: " + jobName);

        // get the ID of the Next Job to run.
        if (this.getPreventRemoteBuildQueue()) {
            listener.getLogger().println("Checking that the remote job " + jobName + " is not currently building.");
            String preCheckUrlString = this.buildGetUrl(jobName, securityToken);
            preCheckUrlString += "/lastBuild";
            preCheckUrlString += "/api/json/";
            JSONObject preCheckResponse = sendHTTPCall(preCheckUrlString, "GET", build, listener);
            
            if ( preCheckResponse != null ) {
                // check the latest build on the remote server to see if it's running - if so wait until it has stopped.
                // if building is true then the build is running
                // if result is null the build hasn't finished - but might not have started running.
                while (preCheckResponse.getBoolean("building") == true || preCheckResponse.getString("result") == null) {
                    listener.getLogger().println("Remote build is currently running. Waiting for it to finish - "+ this.pollInterval + " seconds until next retry.");
                    // Sleep for 'pollInterval' seconds.
                    // Sleep takes miliseconds so need to convert this.pollInterval to milisecopnds (x 1000)
                    try {
                        Thread.sleep(this.pollInterval * 1000);
                    } catch (InterruptedException e) {
                        this.failBuild(e, listener);
                    }
                    preCheckResponse = sendHTTPCall(preCheckUrlString, "POST", build, listener);
                }
                listener.getLogger().println("Remote job remote job " + jobName + " is not currenlty building.");    
            } else {
                this.failBuild(new Exception("Got a blank response from Remote Jenkins Server, cannot continue."), listener);
            }

        } else {
            listener.getLogger().println("Not checking if the remote job " + jobName + " is building.");
        }

        String queryUrlString = this.buildGetUrl(jobName, securityToken);
        queryUrlString += "/api/json/";

        //listener.getLogger().println("Getting ID of next job to build. URL: " + queryUrlString);
        JSONObject queryResponseObject = sendHTTPCall(queryUrlString, "GET", build, listener);
        if (queryResponseObject == null ) {
            //This should not happen as this page should return a JSON object
            this.failBuild(new Exception("Got a blank response from Remote Jenkins Server [" + remoteServerURL + "], cannot continue."), listener);
        }
        
        int nextBuildNumber = queryResponseObject.getInt("nextBuildNumber");
        listener.getLogger().println("This job is build #[" + Integer.toString(nextBuildNumber) + "] on the remote server.");

        if (this.getOverrideAuth()) {
            listener.getLogger().println(
                    "Using job-level defined credentails in place of those from remote Jenkins config ["
                            + this.getRemoteJenkinsName() + "]");
        }

        listener.getLogger().println("Triggering remote job now.");
        sendHTTPCall(triggerUrlString, "POST", build, listener);
        
        //Have to form the string ourselves, as we might not get a response from non-parameterized builds
        String jobURL = remoteServerURL + "/job/" + this.encodeValue(job) + "/";

        // This is only for Debug
        // This output whether there is another job running on the remote host that this job had conflicted with.
        // The first condition is what is expected, The second is what would happen if two jobs launched jobs at the
        // same time (and two remote builds were triggered).
        // The third is what would happen if this job was triggers and the remote queue was already full (as the 'next
        // build bumber' would still be the same after this job has triggered the remote job)
        // int newNextBuildNumber = responseObject.getInt( "nextBuildNumber" ); // This should be nextBuildNumber + 1 OR
        // there has been another job scheduled.
        // if (newNextBuildNumber == (nextBuildNumber + 1)) {
        // listener.getLogger().println("DEBUG: No other jobs triggered" );
        // } else if( newNextBuildNumber > (nextBuildNumber + 1) ) {
        // listener.getLogger().println("DEBUG: WARNING Other jobs triggered," + newNextBuildNumber + ", " +
        // nextBuildNumber );
        // } else {
        // listener.getLogger().println("DEBUG: WARNING Did not get the correct build number for the triggered job, previous nextBuildNumber:"
        // + newNextBuildNumber + ", newNextBuildNumber" + nextBuildNumber );
        // }

        // If we are told to block until remoteBuildComplete:
        if (this.getBlockBuildUntilComplete()) {
            listener.getLogger().println("Blocking local job until remote job completes");
            // Form the URL for the triggered job
            String jobLocation = jobURL + nextBuildNumber + "/api/json";

            buildStatusStr = getBuildStatus(jobLocation, build, listener);

            while (buildStatusStr.equals("not started")) {
                listener.getLogger().println("Waiting for remote build to start - " + this.pollInterval + " seconds until next poll.");

                // Sleep for 'pollInterval' seconds.
                // Sleep takes miliseconds so need to convert this.pollInterval to milisecopnds (x 1000)
                try {
                    // Could do with a better way of sleeping...
                    Thread.sleep(this.pollInterval * 1000);
                } catch (InterruptedException e) {
                    this.failBuild(e, listener);
                }
                buildStatusStr = getBuildStatus(jobLocation, build, listener);
            }

            listener.getLogger().println("Remote build started!");
            while (buildStatusStr.equals("running")) {
                listener.getLogger().println("Waiting for remote build to finish - " + this.pollInterval + " seconds until next poll.");
                
                // Sleep for 'pollInterval' seconds.
                // Sleep takes miliseconds so need to convert this.pollInterval to milisecopnds (x 1000)
                try {
                    // Could do with a better way of sleeping...
                    Thread.sleep(this.pollInterval * 1000);
                } catch (InterruptedException e) {
                    this.failBuild(e, listener);
                }
                buildStatusStr = getBuildStatus(jobLocation, build, listener);
            }
            listener.getLogger().println("Remote build finished with status " + buildStatusStr + ".");

            // If build did not finish with 'success' then fail build step.
            if (!buildStatusStr.equals("SUCCESS")) {
                // failBuild will check if the 'shouldNotFailBuild' parameter is set or not, so will decide how to
                // handle the failure.
                this.failBuild(new Exception("The remote job did not succeed."), listener);
            }
        } else {
            listener.getLogger().println("Not blocking local job until remote job completes - fire and forget.");
        }
        
        
        //Create EnvVars hash and use this to set EnvVars for job.
        EnvVars buildEnvVars = build.getEnvironment(listener);
        HashMap<String, String> remoteBuildEnvVars = getEnvVarsMap(listener, buildEnvVars, jobName, nextBuildNumber, buildStatusStr);
        
        //Set the new build variables map
        build.addAction(new RemoteBuildEnvInjectAction(remoteBuildEnvVars));
        
        return true;
    }
    
    /**
     * Creates a HashMap of Environment Variables to set for the Job, as defined in Parameterized Build plugin:
     *  - LAST_TRIGGERED_JOB_NAME="Last project started"
     *  - TRIGGERED_BUILD_NUMBER_<project name>="Last build number triggered"
     *  - TRIGGERED_JOB_NAMES="Comma separated list of all triggered projects"
     *  - TRIGGERED_BUILD_NUMBERS_<project name>="Comma separated list of build numbers triggered"
     *  - TRIGGERED_BUILD_RESULT_<project name>="Last triggered build result of project"
     *  - TRIGGERED_BUILD_RESULT_<project name>_RUN_<build number>="Result of triggered build for build number"
     *  - TRIGGERED_BUILD_RUN_COUNT_project name>="Number of builds triggered for the project"
     * @param env
     * @param jobName
     * @param buildNum
     * @param buildStatusStr
     * @return HasMap<String,String> A HashMap of Environment Variables.
     */
    public HashMap<String, String> getEnvVarsMap(BuildListener listener, EnvVars env, String jobName, int nextBuildNumber, String buildStatusStr ) {
        
        HashMap<String, String> remoteBuildEnvVars = new HashMap<String, String>();
        //replace any non-alpha numeric character in job name with an underscore.
        String sanitisedJobName = jobName.replaceAll("[^a-zA-Z0-9]+", "_");
        String buildNumberStr = Integer.toString(nextBuildNumber);
        
        //Set EnvVar "LAST_TRIGGERED_JOB_NAME" to a sanitized sanitisedJobName.
        remoteBuildEnvVars.put("LAST_TRIGGERED_JOB_NAME", jobName );

        //Set EnvVar "TRIGGERED_BUILD_NUMBER_<project name>" to stringified nextBuildNumber.
        remoteBuildEnvVars.put("TRIGGERED_BUILD_NUMBER_" + sanitisedJobName, buildNumberStr);
        
        //Set EnvVar "TRIGGERED_JOB_NAMES" to previous value of "TRIGGERED_JOB_NAMES" + ,jobName.
        String buildJobNames = env.get("TRIGGERED_JOB_NAMES");
        String updatedBuildJobNames;
        if ( buildJobNames == null ) { 
            updatedBuildJobNames = jobName;
        } else {
            updatedBuildJobNames = buildJobNames + "," + jobName;
        }
        
        remoteBuildEnvVars.put("TRIGGERED_JOB_NAMES", updatedBuildJobNames );        
        
        //Set EnvVar "TRIGGERED_BUILD_NUMBERS_project name>" to previousValue +1".
        String triggedBuildNumbers = env.get("TRIGGERED_JOB_NAMES");
        String updatedTriggeredBuildNumbers;
        if ( triggedBuildNumbers == null ) { 
            updatedTriggeredBuildNumbers = buildNumberStr;
        } else {
            updatedTriggeredBuildNumbers = triggedBuildNumbers + "," + buildNumberStr;
        }
        
        remoteBuildEnvVars.put("TRIGGERED_BUILD_NUMBERS_" + sanitisedJobName, updatedTriggeredBuildNumbers );
        
        //Set EnvVar "TRIGGERED_BUILD_NUMBERS_<project name>" to returned BuildStatus
        remoteBuildEnvVars.put("TRIGGERED_BUILD_RESULT_" + sanitisedJobName, buildStatusStr);
        
        //Set EnvVar "TRIGGERED_BUILD_NUMBERS_<project name>" to returned BuildStatus
        remoteBuildEnvVars.put("TRIGGERED_BUILD_RESULT_" + sanitisedJobName + "_RUN_" + buildNumberStr, buildStatusStr);

        //Set EnvVar "TRIGGERED_BUILD_RUN_COUNT_project name>" to previousValue +1.
        String previousRunCountStr = env.get("TRIGGERED_BUILD_RUN_COUNT_" + sanitisedJobName );
        int previousRunCount;
        if ( previousRunCountStr == null ) { 
            previousRunCount = 0;
        } else {
            previousRunCount = Integer.parseInt( previousRunCountStr );
        }
        previousRunCount++;
        
        remoteBuildEnvVars.put( "TRIGGERED_BUILD_RUN_COUNT_" + sanitisedJobName, Integer.toString(previousRunCount) );
        
        //Debug Messaging:
        //listener.getLogger().println("Setting: " + "'LAST_TRIGGERED_JOB_NAME'" + ", to '" + jobName + "'");
        //listener.getLogger().println("Setting: " + "'TRIGGERED_JOB_NAMES" + "', to '" + updatedBuildJobNames + "'");
        //listener.getLogger().println("Setting: " + "'TRIGGERED_BUILD_NUMBERS_" + sanitisedJobName + "', to '" + updatedBuildJobNames + "'");
        //listener.getLogger().println("Setting: " + "'TRIGGERED_BUILD_RESULT_" + sanitisedJobName + "', to '" + updatedBuildJobNames + "'");
        //listener.getLogger().println("Setting: " + "'TRIGGERED_BUILD_RESULT_" + sanitisedJobName + "_RUN_" + buildNumberStr + "', to '" + buildStatusStr + "'");
        //listener.getLogger().println("Setting: " + "'TRIGGERED_BUILD_RUN_COUNT_" + sanitisedJobName + "', to '" + Integer.toString(previousRunCount) + "'");
        return remoteBuildEnvVars;
    }

    public String getBuildStatus(String buildUrlString, AbstractBuild build, BuildListener listener) throws IOException {
        String buildStatus = "UNKNOWN";

        RemoteJenkinsServer remoteServer = this.findRemoteHost(this.getRemoteJenkinsName());

        if (remoteServer == null) {
            this.failBuild(new Exception("No remote host is defined for this job."), listener);
            return null;
        }

        // print out some debugging information to the console
        //listener.getLogger().println("Checking Status of this job: " + buildUrlString);
        if (this.getOverrideAuth()) {
            listener.getLogger().println(
                    "Using job-level defined credentails in place of those from remote Jenkins config ["
                            + this.getRemoteJenkinsName() + "]");
        }

        JSONObject responseObject = sendHTTPCall(buildUrlString, "GET", build, listener);

        // get the next build from the location

        if (responseObject == null || responseObject.getString("result") == null && responseObject.getBoolean("building") == false) {
            // build not started
            buildStatus = "not started";
        } else if (responseObject.getBoolean("building")) {
            // build running
            buildStatus = "running";
        } else if (responseObject.getString("result") != null) {
            // build finished
            buildStatus = responseObject.getString("result");
        } else {
            // Add additional else to check for unhandled conditions
            listener.getLogger().println("WARNING: Unhandled condition!");
        }

        return buildStatus;
    }

    public JSONObject sendHTTPCall(String urlString, String requestType, AbstractBuild build, BuildListener listener)
            throws IOException {
        
            return sendHTTPCall( urlString, requestType, build, listener, 1 );
    }

    public JSONObject sendHTTPCall(String urlString, String requestType, AbstractBuild build, BuildListener listener, int NumberOfAttempts)
            throws IOException {
        RemoteJenkinsServer remoteServer = this.findRemoteHost(this.getRemoteJenkinsName());

        if (remoteServer == null) {
            this.failBuild(new Exception("No remote host is defined for this job."), listener);
            return null;
        }

        HttpURLConnection connection = null;

        JSONObject responseObject = null;

        try {
            URL buildUrl = new URL(urlString);
            connection = (HttpURLConnection) buildUrl.openConnection();

            // if there is a username + apiToken defined for this remote host, then use it
            String usernameTokenConcat;

            if (this.getOverrideAuth()) {
                usernameTokenConcat = this.getAuth()[0].getUsername() + ":" + this.getAuth()[0].getPassword();
            } else {
                usernameTokenConcat = remoteServer.getAuth()[0].getUsername() + ":"
                        + remoteServer.getAuth()[0].getPassword();
            }

            if (!usernameTokenConcat.equals(":")) {
                // token-macro replacment
                usernameTokenConcat = TokenMacro.expandAll(build, listener, usernameTokenConcat);

                byte[] encodedAuthKey = Base64.encodeBase64(usernameTokenConcat.getBytes());
                connection.setRequestProperty("Authorization", "Basic " + new String(encodedAuthKey));
            }

            connection.setDoInput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestMethod(requestType);
            // wait up to 5 seconds for the connection to be open
            connection.setConnectTimeout(5000);
            connection.connect();

            InputStream is = connection.getInputStream();

            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
            String line;
            // String response = "";
            StringBuilder response = new StringBuilder();

            while ((line = rd.readLine()) != null) {
                response.append(line);
            }
            rd.close();

            // JSONSerializer serializer = new JSONSerializer();
            // need to parse the data we get back into struct
            //listener.getLogger().println("Called URL: '" + urlString +  "', got response: '" + response.toString() + "'");
            if ( response.toString().isEmpty() ) {
                listener.getLogger().println("Remote Jenkins server returned empty response to trigger.");
                return null;
            } else {
                responseObject = (JSONObject) JSONSerializer.toJSON(response.toString());
            }

        } catch (IOException e) {
            //If we have ConnectionRetryLimit set to > 0 then retry that many times.
            if ( NumberOfAttempts <= this.getConnectionRetryLimit() ) {
                String strNumberOfRetries = Integer.toString(NumberOfAttempts);
                String strConnectionRetryLimit = Integer.toString(this.getConnectionRetryLimit() );
                        
                listener.getLogger().println("Connection to remote server failed, retrying (attempt  " + strNumberOfRetries + " out of " + strConnectionRetryLimit + ")");
                responseObject = sendHTTPCall(urlString, requestType, build, listener, NumberOfAttempts+1);
            }
            // something failed with the connection, so throw an exception to mark the build as failed.
            else {
                this.failBuild(e, listener);
            }
        } catch (MacroEvaluationException e) {
            this.failBuild(e, listener);
        } catch (InterruptedException e) {
            this.failBuild(e, listener);
        } finally {
            // always make sure we close the connection
            if (connection != null) {
                connection.disconnect();
            }
            // and always clear the query string and remove some "global" values
            this.clearQueryString();
            // this.build = null;
            // this.listener = null;

        }
        return responseObject;
    }

    /**
     * Helper function for character encoding
     * 
     * @param dirtyValue
     * @return encoded value
     */
    private String encodeValue(String dirtyValue) {
        String cleanValue = "";

        try {
            cleanValue = URLEncoder.encode(dirtyValue, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return cleanValue;
    }

    // Getters
    public String getRemoteJenkinsName() {
        return this.remoteJenkinsName;
    }

    public String getJob() {
        return this.job;
    }

    public boolean getShouldNotFailBuild() {
        return this.shouldNotFailBuild;
    }

    public boolean getPreventRemoteBuildQueue() {
        return this.preventRemoteBuildQueue;
    }

    public boolean getBlockBuildUntilComplete() {
        return this.blockBuildUntilComplete;
    }

    public int getPollInterval() {
        return this.pollInterval;
    }

    public int getConnectionRetryLimit() {
        return this.connectionRetryLimit;
    }
        
    public String getToken() {
        return this.token;
    }

    private String getParameterFile() {
        return this.parameterFile;
    }

    private String getBuildTypeUrl() {
        boolean isParameterized = (this.getParameters().length() > 0);

        if (isParameterized) {
            return RemoteBuildConfiguration.paramerizedBuildUrl;
        } else {
            return RemoteBuildConfiguration.normalBuildUrl;
        }
    }

    public boolean getOverrideAuth() {
        return this.overrideAuth;
    }

    public Auth[] getAuth() {
        return auth.toArray(new Auth[this.auth.size()]);

    }

    public String getParameters() {
        return this.parameters;
    }

    private List<String> getParameterList() {
        return this.parameterList;
    }

    public String getQueryString() {
        return this.queryString;
    }

    private void setQueryString(String string) {
        this.queryString = string.trim();
    }

    /**
     * Convenience function for setting the query string to empty
     */
    private void clearQueryString() {
        this.setQueryString("");
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    // This indicates to Jenkins that this is an implementation of an extension
    // point.
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information, simply store it in a field and call save().
         * 
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */
        private CopyOnWriteList<RemoteJenkinsServer> remoteSites = new CopyOnWriteList<RemoteJenkinsServer>();

        /**
         * In order to load the persisted global configuration, you have to call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'name'.
         * 
         * @param value
         *            This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         */
        /*
         * public FormValidation doCheckName(@QueryParameter String value) throws IOException, ServletException { if
         * (value.length() == 0) return FormValidation.error("Please set a name"); if (value.length() < 4) return
         * FormValidation.warning("Isn't the name too short?"); return FormValidation.ok(); }
         */

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project
            // types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Trigger a remote parameterized job";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {

            remoteSites.replaceBy(req.bindJSONToList(RemoteJenkinsServer.class, formData.get("remoteSites")));
            save();

            return super.configure(req, formData);
        }

        public ListBoxModel doFillRemoteJenkinsNameItems() {
            ListBoxModel model = new ListBoxModel();

            for (RemoteJenkinsServer site : getRemoteSites()) {
                model.add(site.getDisplayName());
            }

            return model;
        }

        public RemoteJenkinsServer[] getRemoteSites() {

            return remoteSites.toArray(new RemoteJenkinsServer[this.remoteSites.size()]);
        }
    }
}

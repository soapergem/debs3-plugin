package jenkins.plugins.debs3;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;

public class DebS3Plugin extends Recorder {

    private List<DebS3> entries = Collections.emptyList();

    @DataBoundConstructor
    public DebS3Plugin(List<DebS3> debs3) {
        this.entries = debs3;
        if (this.entries == null) {
            this.entries = Collections.emptyList();
        }
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    private boolean isPerformDeployment(AbstractBuild build) {
        Result result = build.getResult();
        if (result == null) {
            return true;
        }

        return build.getResult().isBetterOrEqualTo(Result.UNSTABLE);
    }

    @SuppressWarnings("unused")
    public List<DebS3> getEntries() {
        return entries;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        if (isPerformDeployment(build)) {

            if (entries == null || entries.isEmpty()) {
                listener.getLogger().println("[DebS3Plugin] - step is not properly configured");
                return false;
            }

            listener.getLogger().println("[DebS3Plugin] - Publishing to Debian repo ...");
            for (DebS3 debEntry : entries) {
                StringTokenizer debGlobTokenizer = new StringTokenizer(debEntry.getIncludes(), ",");

                GpgKey gpgKey = getGpgKey(debEntry.getGpgKeyName());
                if (gpgKey != null && gpgKey.getPrivateKey().getPlainText().length() > 0) {
                    listener.getLogger().println("[DebS3Plugin] - Importing private key");
                    importGpgKey(gpgKey.getPrivateKey().getPlainText(), build, launcher, listener);
                    listener.getLogger().println("[DebS3Plugin] - Imported private key");
                }

                if (!isGpgKeyAvailable(gpgKey, build, launcher, listener)){
                    listener.getLogger().println("[DebS3Plugin] - Can't find GPG key: " + debEntry.getGpgKeyName());
                    return false;
                }

                while (debGlobTokenizer.hasMoreTokens()) {
                    String debGlob = debGlobTokenizer.nextToken();

                    listener.getLogger().println("[DebS3Plugin] - Publishing " + debGlob);

                    FilePath[] matchedDebs = build.getWorkspace().list(debGlob);
                    if (ArrayUtils.isEmpty(matchedDebs)) {
                        listener.getLogger().println("[DebS3Plugin] - No DEBs matching " + debGlob);
                    } else {
                        for (FilePath debFilePath : matchedDebs) {

                            ArgumentListBuilder debS3Command = new ArgumentListBuilder();
                            debS3Command.add("deb-s3", "upload");
                            debS3Command.addTokenized(debEntry.getCmdlineOpts());
                            debS3Command.add("--bucket", debEntry.getBucket());
                            debS3Command.addQuoted(debFilePath.toURI().normalize().getPath());
                            debS3Command.add("--sign", gpgKey.getName());

                            String debCommandLine = debS3Command.toString();
                            listener.getLogger().println("[DebS3Plugin] - Running " + debCommandLine);

                            ArgumentListBuilder expectCommand = new ArgumentListBuilder();
                            expectCommand.add("expect", "-");

                            Launcher.ProcStarter ps = launcher.new ProcStarter();
                            ps = ps.cmds(expectCommand).stdout(listener);
                            ps = ps.pwd(build.getWorkspace()).envs(build.getEnvironment(listener));

                            byte[] expectScript = createExpectScriptFile(debCommandLine, gpgKey.getPassphrase().getPlainText());
                            ByteArrayInputStream is = new ByteArrayInputStream(expectScript);
                            ps.stdin(is);

                            Proc proc = launcher.launch(ps);
                            int retcode = proc.join();
                            if (retcode != 0) {
                                listener.getLogger().println("[DebS3Plugin] - Failed publishing repository ...");
                                return false;
                            }
                        }
                    }
                }
            }

            listener.getLogger().println("[DebS3Plugin] - Finished publishing to Debian repo ...");
        } else {
            listener.getLogger().println("[DebS3Plugin] - Skipping publishing to Debian repo ...");
        }
        return true;
    }

    private byte[] createExpectScriptFile(String signCommand, String passphrase)
            throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(512);

        final PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos));
        try {
            writer.print("spawn ");
            writer.println(signCommand);
            writer.println("expect {");
            writer.print("-re \"Enter pass *phrase: *\" { log_user 0; send -- \"");
            writer.print(passphrase);
            writer.println("\r\"; log_user 1; }");
            writer.println("eof { catch wait rc; exit [lindex $rc 3]; }");
            writer.println("timeout { close; exit; }");
            writer.println("}");
            writer.println("expect {");
            writer.println("eof { catch wait rc; exit [lindex $rc 3]; }");
            writer.println("timeout close");
            writer.println("}");
            writer.println();

            writer.flush();
        } finally {
            writer.close();
        }

        return baos.toByteArray();
    }

    private void importGpgKey(String privateKey, AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        ArgumentListBuilder command = new ArgumentListBuilder();
        command.add("gpg", "--import", "-");
        Launcher.ProcStarter ps = launcher.new ProcStarter();
        ps = ps.cmds(command).stdout(listener);
        ps = ps.pwd(build.getWorkspace()).envs(build.getEnvironment(listener));

        InputStream is = new ByteArrayInputStream(privateKey.getBytes());

        ps.stdin(is);
        Proc proc = launcher.launch(ps);
        proc.join();
        is.close();
    }

    private boolean isGpgKeyAvailable(GpgKey gpgKey, AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        ArgumentListBuilder command = new ArgumentListBuilder();
        command.add("gpg", "--fingerprint", gpgKey.getName());
        Launcher.ProcStarter ps = launcher.new ProcStarter();
        ps = ps.cmds(command).stdout(listener);
        ps = ps.pwd(build.getWorkspace()).envs(build.getEnvironment(listener));
        Proc proc = launcher.launch(ps);

        return proc.join() == 0;
    }

    private GpgKey getGpgKey(String gpgKeyName) {
        GpgSignerDescriptor gpgSignerDescriptor = Jenkins.getInstance().getDescriptorByType(GpgSignerDescriptor.class);
        if (!StringUtils.isEmpty(gpgKeyName) && !gpgSignerDescriptor.getGpgKeys().isEmpty()) {
            for (GpgKey gpgKey : gpgSignerDescriptor.getGpgKeys()) {
                if (StringUtils.equals(gpgKeyName, gpgKey.getName())) {
                    return gpgKey;
                }
            }
        }
        return null;
    }

    @Extension
    @SuppressWarnings("unused")
    public static final class GpgSignerDescriptor extends BuildStepDescriptor<Publisher> {

        public static final String DISPLAY_NAME = Messages.job_displayName();

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        private volatile List<GpgKey> gpgKeys = new ArrayList<GpgKey>();

        public GpgSignerDescriptor() {
            load();
        }

        @Override
        public String getDisplayName() {
            return DISPLAY_NAME;
        }

        public List<GpgKey> getGpgKeys() {
            return gpgKeys;
        }

        public ListBoxModel doFillGpgKeyNameItems() {
            ListBoxModel items = new ListBoxModel();
            for (GpgKey gpgKey : gpgKeys) {
                items.add(gpgKey.getName(), gpgKey.getName());
            }
            return items;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            gpgKeys = req.bindJSONToList(GpgKey.class, json.get("gpgKey"));
            save();
            return true;
        }

        public FormValidation doCheckName(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckPrivateKey(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckPassphrase(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckIncludes(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException, InterruptedException {
            if (project.getSomeWorkspace() != null) {
                String msg = project.getSomeWorkspace().validateAntFileMask(value);
                if (msg != null) {
                    return FormValidation.error(msg);
                }
                return FormValidation.ok();
            } else {
                return FormValidation.warning(Messages.noworkspace());
            }
        }

    }
}

package jenkins.plugins.debs3;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class DebS3 extends AbstractDescribableImpl<DebS3> {
    private final String gpgKeyName;
    private final String bucket;
    private final String includes;
    private final String cmdlineOpts;

    @DataBoundConstructor
    public DebS3(String gpgKeyName, String bucket, String includes, String cmdlineOpts) {
        this.gpgKeyName = gpgKeyName;
        this.bucket = bucket;
        this.includes = includes;
        this.cmdlineOpts = cmdlineOpts;
    }

    public String getGpgKeyName() {
        return gpgKeyName;
    }

    public String getBucket() {
        return bucket;
    }

    public String getIncludes() {
        return includes;
    }

    public String getCmdlineOpts() {
        return cmdlineOpts;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<DebS3> {
        @Override
        public String getDisplayName() {
            return ""; // unused
        }
    }
}

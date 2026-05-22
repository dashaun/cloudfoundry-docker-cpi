package com.dashaun.cfdockercpi.setup;

public final class ManifestVersions {

    public static final String BOSH_DEPLOYMENT_REPO = "https://github.com/cloudfoundry/bosh-deployment.git";
    public static final String BOSH_DEPLOYMENT_SHA = "3ad6e9bc8fcff4b13c27dc77ead11072ef09cd0f";

    public static final String CF_DEPLOYMENT_REPO = "https://github.com/cloudfoundry/cf-deployment.git";
    public static final String CF_DEPLOYMENT_SHA = "5c4fc5f5246e4c5cfc7b488ccf4bc620a2c1d6af";
    public static final String CF_DEPLOYMENT_TAG = "v56.4.0";

    // Warden stemcell pinned to what cf-deployment v56.4.0 requires (ubuntu-noble 1.364).
    public static final String STEMCELL_NAME = "bosh-warden-boshlite-ubuntu-noble";
    public static final String STEMCELL_OS = "ubuntu-noble";
    public static final String STEMCELL_VERSION = "1.364";
    // Direct GCS URL (bosh.io's "noble" alias doesn't resolve; same source bosh-deployment uses).
    public static final String STEMCELL_URL =
            "https://storage.googleapis.com/bosh-core-stemcells/" + STEMCELL_VERSION
                    + "/bosh-stemcell-" + STEMCELL_VERSION + "-warden-boshlite-ubuntu-noble.tgz";

    private ManifestVersions() {}
}

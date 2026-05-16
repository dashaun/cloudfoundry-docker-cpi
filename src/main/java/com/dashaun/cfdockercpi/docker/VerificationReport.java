package com.dashaun.cfdockercpi.docker;

import java.util.List;

public record VerificationReport(String target, String effectiveUri, List<CheckResult> checks) {

    public boolean ok() {
        return checks.stream().noneMatch(c -> c.status() == CheckResult.Status.FAIL);
    }
}

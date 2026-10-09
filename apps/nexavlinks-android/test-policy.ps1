param([string]$CacheDirectory = "$PSScriptRoot/.gradle/policy-tests")
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $CacheDirectory | Out-Null
$dependencies = @(
    @{ Name = 'junit-4.13.2.jar'; Path = 'junit/junit/4.13.2/junit-4.13.2.jar' },
    @{ Name = 'hamcrest-core-1.3.jar'; Path = 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar' },
    @{ Name = 'json-20240303.jar'; Path = 'org/json/json/20240303/json-20240303.jar' }
)
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$jars = foreach ($dependency in $dependencies) {
    $target = Join-Path $CacheDirectory $dependency.Name
    $url = "https://repo.maven.apache.org/maven2/$($dependency.Path)"
    $checksum = (Invoke-WebRequest -UseBasicParsing -Uri "$url.sha1" -TimeoutSec 30).Content.ToString().Trim().ToLowerInvariant()
    if (!(Test-Path $target)) { Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $target -TimeoutSec 30 }
    if ((Get-FileHash -Algorithm SHA1 $target).Hash.ToLowerInvariant() -ne $checksum) {
        throw "Dependency checksum mismatch: $($dependency.Name). Inspect the cached file before retrying."
    }
    $target
}
$classes = Join-Path $CacheDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$classpath = $jars -join [IO.Path]::PathSeparator
$source = "$PSScriptRoot/app/src/main/java/com/nexacheckin"
$tests = "$PSScriptRoot/app/src/test/java/com/nexacheckin"
& javac -encoding UTF-8 -source 8 -target 8 -cp $classpath -d $classes "$source/Policy.java" "$source/Site.java" "$source/RouterApi.java" "$source/RouterProtocol.java" "$tests/RouterTest.java" "$source/Account.java" "$source/Api.java" "$source/Progress.java" "$source/LoginRequest.java" "$source/SnapshotData.java" "$source/Totals.java" "$source/CheckinPresentation.java" "$tests/CheckinPresentationTest.java" "$source/RefreshPolicy.java" "$source/TokenPair.java" "$source/Renewal.java" "$source/ExpiryPolicy.java" "$source/Subscription.java" "$source/QuotaReset.java" "$tests/QuotaResetTest.java" "$tests/SubscriptionTest.java" "$tests/ExpiryPolicyTest.java" "$tests/RenewalTest.java" "$tests/PolicyTest.java" "$tests/ProgressTest.java" "$tests/LoginRequestTest.java" "$tests/SnapshotTest.java" "$tests/RefreshPolicyTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
& java -cp "$classes$([IO.Path]::PathSeparator)$classpath" org.junit.runner.JUnitCore com.nexacheckin.PolicyTest com.nexacheckin.ProgressTest com.nexacheckin.LoginRequestTest com.nexacheckin.SnapshotTest com.nexacheckin.RenewalTest com.nexacheckin.ExpiryPolicyTest com.nexacheckin.SubscriptionTest com.nexacheckin.RefreshPolicyTest com.nexacheckin.CheckinPresentationTest com.nexacheckin.QuotaResetTest com.nexacheckin.RouterTest
if ($LASTEXITCODE -ne 0) { throw 'Policy tests failed' }

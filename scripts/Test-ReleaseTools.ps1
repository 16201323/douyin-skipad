$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Get-ApkSignerDigests.ps1"
$fingerprint = '222bea214ff08813e46394cf9e6d6a0b51d552af1d81474c01e5e5541ce9e429'
$old = @(Get-ApkSignerDigests "Signer #1 certificate SHA-256 digest: $fingerprint")
if ($old.Count -ne 1 -or $old[0] -ne $fingerprint) { throw 'Old build-tools signer output must match.' }
$new = @(Get-ApkSignerDigests "V2 Signer: certificate SHA-256 digest: $fingerprint")
if ($new.Count -ne 1 -or $new[0] -ne $fingerprint) { throw 'New build-tools V2 Signer output must match.' }
$duplicate = @(Get-ApkSignerDigests "Signer #1 certificate SHA-256 digest: $fingerprint`nV2 Signer: certificate SHA-256 digest: $fingerprint")
if ($duplicate.Count -ne 1) { throw 'Repeated verification of the same certificate must be deduplicated.' }
$different = @(Get-ApkSignerDigests "Signer #1 certificate SHA-256 digest: $fingerprint`nSigner #2 certificate SHA-256 digest: $('a'*64)")
if ($different.Count -ne 2) { throw 'Multiple different signers must not be silently reduced to the first.' }
if (@(Get-ApkSignerDigests 'Signer #1 certificate SHA-256 digest: broken').Count -ne 0) { throw 'Invalid digest must be rejected.' }
Write-Output 'Release tooling checks passed: old/new output, duplicate signer, multiple signers and invalid digest.'

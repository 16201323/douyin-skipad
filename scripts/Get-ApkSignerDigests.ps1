function Get-ApkSignerDigests([string]$OutputText) {
    [regex]::Matches($OutputText, '(?im)^(?:Signer #\d+|V\d+ Signer):? certificate SHA-256 digest: ([0-9a-f]{64})\s*$') |
        ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } | Sort-Object -Unique
}

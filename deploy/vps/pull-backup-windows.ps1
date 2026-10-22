# Copies the nightly Orda backups from cloud-001 to this PC (second copy off the server).
# Scheduled task "Orda backup pull": daily 09:00 + at logon (PC may be off at night).
# Keeps 30 DB dumps and the 3 latest file archives (each ~0.7 GB).
# Needs the "cloud-001" alias in ~/.ssh/config (key id_ed25519_cloud001).
$ErrorActionPreference = 'Stop'
$dst = Join-Path $env:USERPROFILE 'Backups\Orda'
New-Item -ItemType Directory -Force $dst | Out-Null
$log = Join-Path $dst 'pull.log'

try {
    $remote = ssh -o BatchMode=yes -o ConnectTimeout=20 cloud-001 'ls -1t /opt/orda/backups/db-*.dump | head -3; ls -1t /opt/orda/backups/storage-*.tgz | head -1'
    foreach ($f in $remote) {
        $name = Split-Path $f -Leaf
        $local = Join-Path $dst $name
        if (-not (Test-Path $local)) {
            scp -q -o BatchMode=yes "cloud-001:$f" "$local.part"
            Move-Item -Force "$local.part" $local
            Add-Content $log "$(Get-Date -Format s) got $name"
        }
    }
    Get-ChildItem $dst -Filter 'db-*.dump' | Sort-Object Name -Descending | Select-Object -Skip 30 | Remove-Item
    Get-ChildItem $dst -Filter 'storage-*.tgz' | Sort-Object Name -Descending | Select-Object -Skip 3 | Remove-Item
    Get-ChildItem $dst -Filter '*.part' | Remove-Item
} catch {
    Add-Content $log "$(Get-Date -Format s) ERROR $($_.Exception.Message)"
    exit 1
}

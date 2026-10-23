# Copies the nightly backups from cloud-001 to this PC (second copy off the server).
# Scheduled task "Orda backup pull": daily 09:00 + at logon (PC may be off at night).
# Orda: keeps 30 DB dumps and the 3 latest file archives (each ~0.7 GB).
# Tour and Turanix (small): keep 30 of each, in Backups\Orda\tour and Backups\Orda\turanix.
# Needs the "cloud-001" alias in ~/.ssh/config (key id_ed25519_cloud001).
$ErrorActionPreference = 'Stop'
$root = Join-Path $env:USERPROFILE 'Backups\Orda'
New-Item -ItemType Directory -Force $root | Out-Null
$log = Join-Path $root 'pull.log'

$sets = @(
    @{ Dst = $root; List = 'ls -1t /opt/orda/backups/db-*.dump | head -3; ls -1t /opt/orda/backups/storage-*.tgz | head -1'; Keep = @{ 'db-*.dump' = 30; 'storage-*.tgz' = 3 } },
    @{ Dst = (Join-Path $root 'tour'); List = 'ls -1t /opt/supabase/tour/backups/tour-db-*.dump /opt/supabase/tour/backups/tour-storage-*.tgz | head -4'; Keep = @{ 'tour-db-*.dump' = 30; 'tour-storage-*.tgz' = 30 } },
    @{ Dst = (Join-Path $root 'turanix'); List = 'ls -1t /opt/supabase/turanix/backups/turanix-db-*.dump /opt/supabase/turanix/backups/turanix-storage-*.tgz | head -4'; Keep = @{ 'turanix-db-*.dump' = 30; 'turanix-storage-*.tgz' = 30 } }
)

$failed = $false
foreach ($set in $sets) {
    try {
        New-Item -ItemType Directory -Force $set.Dst | Out-Null
        $remote = ssh -o BatchMode=yes -o ConnectTimeout=20 cloud-001 $set.List
        foreach ($f in $remote) {
            $name = Split-Path $f -Leaf
            $local = Join-Path $set.Dst $name
            if (-not (Test-Path $local)) {
                scp -q -o BatchMode=yes "cloud-001:$f" "$local.part"
                Move-Item -Force "$local.part" $local
                Add-Content $log "$(Get-Date -Format s) got $name"
            }
        }
        foreach ($pattern in $set.Keep.Keys) {
            Get-ChildItem $set.Dst -Filter $pattern | Sort-Object Name -Descending | Select-Object -Skip $set.Keep[$pattern] | Remove-Item
        }
        Get-ChildItem $set.Dst -Filter '*.part' | Remove-Item
    } catch {
        Add-Content $log "$(Get-Date -Format s) ERROR $($set.Dst): $($_.Exception.Message)"
        $failed = $true
    }
}
if ($failed) { exit 1 }

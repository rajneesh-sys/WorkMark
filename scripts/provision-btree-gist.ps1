param(
    [Parameter(Mandatory = $true)][string]$DatabaseUrl,
    [Parameter(Mandatory = $true)][string]$DatabaseAdminUser,
    [Parameter(Mandatory = $true)][SecureString]$DatabaseAdminPassword
)

$credential = [System.Net.NetworkCredential]::new("", $DatabaseAdminPassword)
$env:PGPASSWORD = $credential.Password
psql "$DatabaseUrl" -U "$DatabaseAdminUser" -v ON_ERROR_STOP=1 `
    -c "CREATE EXTENSION IF NOT EXISTS btree_gist;"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to provision btree_gist. Run this script with a PostgreSQL database administrator."
}

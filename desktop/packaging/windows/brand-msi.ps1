# Puts Convx's art into the installer jpackage made, and has it say "Updating Convx" when an older
# Convx is already installed. jpackage offers no way to set WiX's dialog pictures, so this edits
# the finished MSI's tables instead. Anything unexpected leaves the original MSI untouched.
param(
    [Parameter(Mandatory)] [string] $Msi,
    [Parameter(Mandatory)] [string] $Banner,
    [Parameter(Mandatory)] [string] $Dialog
)
$ErrorActionPreference = 'Stop'

$flags = [Reflection.BindingFlags]
function Call($obj, [string] $name, [object[]] $arguments = @()) {
    $obj.GetType().InvokeMember($name, $flags::InvokeMethod, $null, $obj, $arguments)
}
function Prop($obj, [string] $name, [object[]] $arguments = @()) {
    $obj.GetType().InvokeMember($name, $flags::GetProperty, $null, $obj, $arguments)
}
function SetProp($obj, [string] $name, [object[]] $arguments) {
    [void] $obj.GetType().InvokeMember($name, $flags::SetProperty, $null, $obj, $arguments)
}

$installer = New-Object -ComObject WindowsInstaller.Installer

function Rows($db, [string] $sql, [int] $columns) {
    $view = Call $db 'OpenView' @($sql)
    [void] (Call $view 'Execute')
    $rows = @()
    while ($true) {
        $record = Call $view 'Fetch'
        if ($null -eq $record) { break }
        $rows += , @(1..$columns | ForEach-Object { Prop $record 'StringData' @($_) })
    }
    [void] (Call $view 'Close')
    return , $rows
}

# Runs a statement whose ? marks take the given values in order; a value that is a file path
# wrapped as @{ File = ... } goes in as a stream.
function Run($db, [string] $sql, [object[]] $values = @()) {
    $view = Call $db 'OpenView' @($sql)
    if ($values.Count -gt 0) {
        $record = Call $installer 'CreateRecord' @($values.Count)
        for ($i = 0; $i -lt $values.Count; $i++) {
            $value = $values[$i]
            if ($value -is [hashtable]) { [void] (Call $record 'SetStream' @(($i + 1), $value.File)) }
            elseif ($value -is [int]) { SetProp $record 'IntegerData' @(($i + 1), $value) }
            else { SetProp $record 'StringData' @(($i + 1), [string] $value) }
        }
        [void] (Call $view 'Execute' @($record))
    } else {
        [void] (Call $view 'Execute')
    }
    [void] (Call $view 'Close')
}

$work = "$Msi.branding"
Copy-Item -LiteralPath $Msi -Destination $work -Force
try {
    $db = Call $installer 'OpenDatabase' @($work, 1) # transact

    # 1. The pictures: WiX keeps them in the Binary table under these names.
    $binaries = (Rows $db 'SELECT `Name` FROM `Binary`' 1) | ForEach-Object { $_[0] }
    Write-Host "Binary table: $($binaries -join ', ')"
    $pictures = @{ 'WixUI_Bmp_Banner' = (Resolve-Path $Banner).Path; 'WixUI_Bmp_Dialog' = (Resolve-Path $Dialog).Path }
    foreach ($name in $pictures.Keys) {
        if ($binaries -notcontains $name) { Write-Host "::warning::No $name in this MSI; its picture stays as it was"; continue }
        Run $db 'DELETE FROM `Binary` WHERE `Name` = ?' @($name)
        Run $db 'INSERT INTO `Binary` (`Name`, `Data`) VALUES (?, ?)' @($name, @{ File = $pictures[$name] })
        Write-Host "Replaced $name"
    }

    # 2. "Updating Convx": the welcome page's title and text come from properties, which a
    # set-property action changes when the upgrade search found an older Convx.
    $upgrade = Rows $db 'SELECT `ActionProperty`, `VersionMin`, `VersionMax` FROM `Upgrade`' 3
    Write-Host "Upgrade table: $(($upgrade | ForEach-Object { $_ -join ' / ' }) -join '; ')"
    $found = ($upgrade | Where-Object { [string]::IsNullOrEmpty($_[1]) -and -not [string]::IsNullOrEmpty($_[2]) } | Select-Object -First 1)
    $welcome = Rows $db "SELECT ``Control``, ``Text`` FROM ``Control`` WHERE ``Dialog_`` = 'WelcomeDlg'" 2
    Write-Host "WelcomeDlg: $(($welcome | ForEach-Object { $_ -join ' = ' }) -join ' | ')"
    $sequence = Rows $db 'SELECT `Action`, `Sequence` FROM `InstallUISequence`' 2
    $findRelated = $sequence | Where-Object { $_[0] -eq 'FindRelatedProducts' } | Select-Object -First 1
    $title = $welcome | Where-Object { $_[0] -eq 'Title' } | Select-Object -First 1
    $text = $welcome | Where-Object { $_[0] -eq 'Description' } | Select-Object -First 1
    if ($null -eq $found -or $null -eq $findRelated -or $null -eq $title -or $null -eq $text) {
        Write-Host "::warning::The installer doesn't look as expected; it keeps its usual welcome text"
    } else {
        $productName = (Rows $db "SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = 'ProductName'" 1)[0][0]
        $flag = $found[0]
        $font = if ($title[1] -match '^(\{[^}]*\})') { $Matches[1] } else { '' }
        $titleText = $title[1].Substring($font.Length).Replace('[ProductName]', $productName)
        $bodyText = $text[1].Replace('[ProductName]', $productName)
        Run $db 'INSERT INTO `Property` (`Property`, `Value`) VALUES (?, ?)' @('ConvxWelcomeTitle', $titleText)
        Run $db 'INSERT INTO `Property` (`Property`, `Value`) VALUES (?, ?)' @('ConvxWelcomeText', $bodyText)
        Run $db "UPDATE ``Control`` SET ``Text`` = ? WHERE ``Dialog_`` = 'WelcomeDlg' AND ``Control`` = 'Title'" @("$font[ConvxWelcomeTitle]")
        Run $db "UPDATE ``Control`` SET ``Text`` = ? WHERE ``Dialog_`` = 'WelcomeDlg' AND ``Control`` = 'Description'" @('[ConvxWelcomeText]')
        Run $db 'INSERT INTO `CustomAction` (`Action`, `Type`, `Source`, `Target`) VALUES (?, ?, ?, ?)' @('ConvxUpdatingTitle', 51, 'ConvxWelcomeTitle', "Updating $productName")
        Run $db 'INSERT INTO `CustomAction` (`Action`, `Type`, `Source`, `Target`) VALUES (?, ?, ?, ?)' @('ConvxUpdatingText', 51, 'ConvxWelcomeText', "An older version of $productName is installed on this PC. Click Next to update it to version [ProductVersion]. Your library, playlists and settings stay as they are.")
        $taken = $sequence | ForEach-Object { [int] $_[1] }
        $next = [int] $findRelated[1] + 1
        foreach ($action in 'ConvxUpdatingTitle', 'ConvxUpdatingText') {
            while ($taken -contains $next) { $next++ }
            Run $db 'INSERT INTO `InstallUISequence` (`Action`, `Condition`, `Sequence`) VALUES (?, ?, ?)' @($action, $flag, $next)
            $taken += $next
            Write-Host "$action runs at $next when $flag is set"
        }
    }

    [void] (Call $db 'Commit')
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($db) | Out-Null
    $db = $null
    [GC]::Collect(); [GC]::WaitForPendingFinalizers()

    # Read it back, so a broken table shows here rather than on someone's PC.
    $check = Call $installer 'OpenDatabase' @($work, 0)
    $props = Rows $check "SELECT ``Property``, ``Value`` FROM ``Property`` WHERE ``Property`` = 'ConvxWelcomeTitle' OR ``Property`` = 'ConvxWelcomeText'" 2
    Write-Host "Check: $(($props | ForEach-Object { $_ -join ' = ' }) -join ' | ')"
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($check) | Out-Null
    $check = $null
    [GC]::Collect(); [GC]::WaitForPendingFinalizers()

    Move-Item -LiteralPath $work -Destination $Msi -Force
    Write-Host "Branded $Msi"
} catch {
    Write-Host "::warning::Couldn't brand the installer ($($_.Exception.Message)); shipping it as jpackage made it"
    $db = $null; $check = $null
    [GC]::Collect(); [GC]::WaitForPendingFinalizers()
    Remove-Item -LiteralPath $work -Force -ErrorAction SilentlyContinue
}

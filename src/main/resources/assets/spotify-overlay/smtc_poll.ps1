$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() |
	Where-Object {
		$_.Name -eq 'AsTask' -and
		$_.GetParameters().Count -eq 1 -and
		$_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
	})[0]

function Await-WinRT($WinRTTask, $ResultType) {
	$asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
	$netTask = $asTask.Invoke($null, @($WinRTTask))
	$netTask.Wait(-1) | Out-Null
	$netTask.Result
}

function Get-UnixMsFromDateTime($dt) {
	if ($null -eq $dt) { return 0 }
	try {
		# DateTimeOffset / DateTime from WinRT LastUpdatedTime
		$dto = [DateTimeOffset]$dt
		return [int64]$dto.ToUnixTimeMilliseconds()
	} catch {
		try {
			$epoch = [DateTime]::new(1970, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)
			return [int64]([DateTime]$dt).ToUniversalTime().Subtract($epoch).TotalMilliseconds
		} catch {
			return 0
		}
	}
}

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$mgr = Await-WinRT ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

while ($true) {
	try {
		$sessions = @($mgr.GetSessions())
		$result = @()
		foreach ($s in $sessions) {
			$app = [string]$s.SourceAppUserModelId
			$info = $s.GetPlaybackInfo()
			$tl = $s.GetTimelineProperties()
			$props = Await-WinRT ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
			$lastUpdatedMs = Get-UnixMsFromDateTime $tl.LastUpdatedTime
			$result += [ordered]@{
				app = $app
				title = [string]$props.Title
				artist = [string]$props.Artist
				albumArtist = [string]$props.AlbumArtist
				album = [string]$props.AlbumTitle
				status = [int]$info.PlaybackStatus
				positionMs = [int64]$tl.Position.TotalMilliseconds
				durationMs = [int64]$tl.EndTime.TotalMilliseconds
				lastUpdatedMs = $lastUpdatedMs
			}
		}
		if ($result.Count -eq 0) {
			Write-Output '[]'
		} elseif ($result.Count -eq 1) {
			Write-Output (($result[0] | ConvertTo-Json -Compress -Depth 6))
		} else {
			Write-Output (($result | ConvertTo-Json -Compress -Depth 6))
		}
	} catch {
		$msg = ($_ | Out-String).Trim().Replace('\', '\\').Replace('"', '\"').Replace("`r", ' ').Replace("`n", ' ')
		Write-Output ("{`"error`":`"$msg`"}")
		try {
			$mgr = Await-WinRT ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
		} catch {}
	}
	[Console]::Out.Flush()
	Start-Sleep -Milliseconds 500
}

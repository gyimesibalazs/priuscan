<#
  PriusCAN serial logger for Windows (PowerShell, no Python needed).
  Timestamps every line the ESP emits (JSON + CAN dump) and flags bus-silence gaps,
  so you can find a "power off / ignition off" signal on CAN and measure how long
  the bus stays alive after ignition-off (the supercap time budget).

  Usage (Windows PowerShell 5.1, built into Windows):
    powershell -ExecutionPolicy Bypass -File can_logger.ps1 -Port list      # list COM ports
    powershell -ExecutionPolicy Bypass -File can_logger.ps1 -Port COM5
    powershell -ExecutionPolicy Bypass -File can_logger.ps1 -Port COM5 -Cmd "D1 0 7FF"

  Procedure:
    1. ESP -> PC by USB (stays USB-powered, so it survives ignition-off).
    2. Run this; it sends the dump command (default 'D1 0 6FF' = all broadcasts, deduped).
    3. Ignition ON/ACC for a few seconds, then switch OFF and KEEP LOGGING ~60 s.
       Optionally switch back ON to capture the wake-up.
    4. Ctrl-C to stop. The timestamped log is written next to this script.
#>
param(
  [Parameter(Mandatory=$true)][string]$Port,
  [string]$Cmd = "D1 0 6FF",
  [int]$Baud = 115200
)

if ($Port -eq "list") {
  Write-Host "Available COM ports:"
  [System.IO.Ports.SerialPort]::GetPortNames() | Sort-Object | ForEach-Object { Write-Host "  $_" }
  return
}

$sp = New-Object System.IO.Ports.SerialPort
$sp.PortName  = $Port
$sp.BaudRate  = $Baud
$sp.Parity    = 'None'
$sp.DataBits  = 8
$sp.StopBits  = 'One'
$sp.ReadTimeout = 1000
$sp.NewLine   = "`n"
$sp.Open()
Start-Sleep -Milliseconds 500
$sp.DiscardInBuffer()
$sp.WriteLine($Cmd)
Write-Host "sent: '$Cmd'  (dump on)"

$file   = "priuscan-canlog-{0:yyyyMMdd-HHmmss}.txt" -f (Get-Date)
$path   = Join-Path $PSScriptRoot $file
$writer = [System.IO.StreamWriter]::new($path)
$t0     = Get-Date
$lastRx = $t0
Write-Host "logging to $path   (Ctrl-C to stop)"

try {
  while ($true) {
    $line = $null
    try { $line = $sp.ReadLine() }
    catch [TimeoutException] {
      $now = Get-Date
      if (($now - $lastRx).TotalSeconds -gt 2) {
        $ms  = [int]($now - $t0).TotalMilliseconds
        $msg = "{0,9} {1:HH:mm:ss} # --- silence {2:0.0}s ---" -f $ms, $now, ($now - $lastRx).TotalSeconds
        Write-Host $msg -ForegroundColor Yellow
        $writer.WriteLine($msg); $writer.Flush()
        $lastRx = $now
      }
      continue
    }
    $now = Get-Date
    $lastRx = $now
    if ($null -eq $line) { continue }
    $line = $line.TrimEnd("`r", "`n")
    if ($line -eq "") { continue }
    $ms    = [int]($now - $t0).TotalMilliseconds
    $stamp = "{0,9} {1:HH:mm:ss.fff}" -f $ms, $now
    $out   = "$stamp $line"
    Write-Host $out
    $writer.WriteLine($out); $writer.Flush()
  }
}
finally {
  try { $sp.WriteLine("D0") } catch {}
  if ($writer) { $writer.Close() }
  if ($sp.IsOpen) { $sp.Close() }
  Write-Host "`nstopped. log: $path"
}

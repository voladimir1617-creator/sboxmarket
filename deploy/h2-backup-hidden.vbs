' Hidden launcher for the H2 money-database backup (deploy\h2-backup.ps1).
'
' WHY A .VBS AND NOT powershell.exe DIRECTLY. powershell.exe is a CONSOLE-subsystem
' binary: Task Scheduler creates its console window BEFORE -WindowStyle Hidden inside
' it can apply, so a window flashes on every run. wscript.exe is GUI-subsystem and
' never has a console at all, and Run(..., 0, ...) starts PowerShell with a hidden
' window from the first instruction. Same pattern, and the same reason, as
' cs2bot's ops\keepalive-hidden.vbs. Never point a scheduled task at powershell.exe
' or bash.exe -- the operator has said repeatedly that he hates console pop-ups.
'
' WHY True AND NOT False ON THE THIRD ARGUMENT -- this is the load-bearing difference
' from deploy\backup-db-hidden.vbs as originally written. Run(cmd, 0, False) returns
' IMMEDIATELY with 0, so wscript exits 0 no matter what the script goes on to do, and
' Task Scheduler records success for a backup that failed. A daily job that can only
' ever report "0" is exactly the missing signal this repo keeps paying for: 150
' consecutive failures written into a log nobody read. Waiting costs about two
' seconds and buys a LastTaskResult that means something.
'
' HOW TO SEE WHETHER IT RAN AT ALL. Task Scheduler's LastTaskResult only exists if
' the scheduler fired. The job also overwrites data\h2-backup-status.json on every
' run; a stale last_run_at there is how a STOPPED SCHEDULER becomes visible, which
' nothing inside this file could ever report.
Option Explicit
Dim sh, fso, here, repo, cmd, rc

Set sh  = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

' deploy\ -> repo root, so moving the checkout does not silently break the task.
here = fso.GetParentFolderName(WScript.ScriptFullName)
repo = fso.GetParentFolderName(here)
sh.CurrentDirectory = repo

cmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & _
      repo & "\deploy\h2-backup.ps1"""

' 0 = hidden window. True = wait, so the exit code below is the script's own.
rc = sh.Run(cmd, 0, True)
WScript.Quit rc

' Hidden launcher for backup-db.sh -- kills the console window the daily backup
' task used to open, and gives the script an ABSOLUTE path.
'
' The task previously ran `bash.exe -c "bash deploy/backup-db.sh >> ..."` with an
' EMPTY WorkingDirectory, so the relative path resolved against system32 and the
' task failed with exit 127 every day from April to September. The failure was
' written to backup.log 150 times and nothing ever read it.
'
' bash.exe is console-subsystem: Task Scheduler creates its console window before
' anything inside can hide it. wscript.exe is GUI-subsystem and Run(...,0) starts
' bash with no window from the first instruction -- the same pattern as
' keepalive-hidden.vbs and skinbox-watchdog-hidden.vbs.
'
' NOTE (2026-09-02): this launcher drives the POSTGRES backup, and this deployment
' does not currently run Postgres -- the money lives in H2 at data\sboxmarket.mv.db.
' The scheduled task has been pointed at deploy\h2-backup-hidden.vbs instead. This
' file is kept, working, for a Postgres deployment; backup-db.sh now says plainly
' which case it is in rather than failing with a bare non-zero forever.
Option Explicit
Dim sh, fso, here, repo, cmd, rc
Set sh  = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

' deploy\ -> repo root, so moving the checkout does not silently break the task.
here = fso.GetParentFolderName(WScript.ScriptFullName)
repo = fso.GetParentFolderName(here)
sh.CurrentDirectory = repo

cmd = """C:\Program Files\Git\bin\bash.exe"" -c ""bash '" & _
      Replace(repo, "\", "/") & "/deploy/backup-db.sh' >> " & _
      "/c/Users/WW/skinbox-backups/backup.log 2>&1"""

' 0 = hidden window. True = WAIT, so the exit code below is the script's own.
'
' This was False, which returns immediately with 0 and makes wscript exit 0 no matter
' what backup-db.sh goes on to do -- so Task Scheduler would record SUCCESS for a
' backup that never happened. That is the same missing-signal-read-as-success defect
' that let a dead pg_dump print "Backup OK" behind a successful gzip. A backup job
' whose LastTaskResult is always 0 reports nothing at all.
rc = sh.Run(cmd, 0, True)
WScript.Quit rc

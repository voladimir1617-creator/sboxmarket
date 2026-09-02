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
Option Explicit
Dim sh, fso, here, repo, cmd
Set sh  = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

' deploy\ -> repo root, so moving the checkout does not silently break the task.
here = fso.GetParentFolderName(WScript.ScriptFullName)
repo = fso.GetParentFolderName(here)
sh.CurrentDirectory = repo

cmd = """C:\Program Files\Git\bin\bash.exe"" -c ""bash '" & _
      Replace(repo, "\", "/") & "/deploy/backup-db.sh' >> " & _
      "/c/Users/WW/skinbox-backups/backup.log 2>&1"""

' 0 = hidden window, False = do not wait.
sh.Run cmd, 0, False

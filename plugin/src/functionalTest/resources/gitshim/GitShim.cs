// The Windows half of the functional tests' git shim; see GitShim.kt. Compiled by the .NET
// Framework's csc.exe, so C# 5: no string interpolation.
//
// Reads shim.conf beside it: real=<git.exe>, log=<file> (optional), fail=<substring> and
// failcommand=<first argument> (each optional, may repeat). Logs the call, exits 128 on a match,
// and otherwise runs the real git with the same arguments, sharing its input and output.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;

class GitShim
{
    static int Main(string[] args)
    {
        string dir = AppDomain.CurrentDomain.BaseDirectory;
        string real = null, log = null;
        var fail = new List<string>();
        var failCommand = new List<string>();
        foreach (string line in File.ReadAllLines(Path.Combine(dir, "shim.conf")))
        {
            int eq = line.IndexOf('=');
            if (eq < 0) continue;
            string key = line.Substring(0, eq), value = line.Substring(eq + 1);
            if (key == "real") real = value;
            else if (key == "log") log = value;
            else if (key == "fail") fail.Add(value);
            else if (key == "failcommand") failCommand.Add(value);
        }
        string joined = string.Join(" ", args);
        if (log != null) Append(log, joined + "\n");
        foreach (string each in fail) if (joined.Contains(each)) return 128;
        if (args.Length > 0 && failCommand.Contains(args[0])) return 128;

        var quoted = new StringBuilder();
        foreach (string arg in args) quoted.Append(quoted.Length == 0 ? "" : " ").Append(Quote(arg));
        // No redirection: the child inherits this process's input and output handles.
        var start = new ProcessStartInfo(real, quoted.ToString()) { UseShellExecute = false };
        using (Process git = Process.Start(start))
        {
            git.WaitForExit();
            return git.ExitCode;
        }
    }

    // Concurrent git calls append to one log, and Windows lets one writer at a time open it, so a
    // refused open is retried. A call still unlogged after that fails loudly: a count that silently
    // dropped calls would read as fewer git calls than the build made.
    static void Append(string log, string line)
    {
        for (int attempt = 1; ; attempt++)
        {
            try { File.AppendAllText(log, line); return; }
            catch (IOException) { if (attempt == 200) throw; System.Threading.Thread.Sleep(5); }
        }
    }

    // The quoting CommandLineToArgvW undoes, so git receives each argument as it arrived.
    static string Quote(string arg)
    {
        if (arg.Length > 0 && arg.IndexOfAny(new[] { ' ', '\t', '\n', '\v', '"' }) < 0) return arg;
        var quoted = new StringBuilder("\"");
        for (int i = 0; ; i++)
        {
            int backslashes = 0;
            while (i < arg.Length && arg[i] == '\\') { backslashes++; i++; }
            if (i == arg.Length) { quoted.Append('\\', backslashes * 2); break; }
            if (arg[i] == '"') quoted.Append('\\', backslashes * 2 + 1).Append('"');
            else quoted.Append('\\', backslashes).Append(arg[i]);
        }
        return quoted.Append('"').ToString();
    }
}

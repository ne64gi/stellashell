#!/usr/bin/env python3
"""Offline launcher integration checks; never invokes real adb/scrcpy."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--pwsh', help='Also exercise the Windows PS1 with mocked executables on PowerShell 7')
opts = parser.parse_args()
root = Path(__file__).resolve().parents[2]
mock = '''#!/usr/bin/env python3
import sys,os,json
from pathlib import Path
args=sys.argv[1:]
with open(os.environ['MOCK_LOG'],'a') as f:f.write(json.dumps([Path(sys.argv[0]).stem,args])+'\\n')
if Path(sys.argv[0]).stem=='scrcpy':sys.exit(0)
if args[0]=='pair':
 if os.environ.get('MOCK_PAIR_CODE'):
  code=os.environ['MOCK_PAIR_CODE']
 else:
  code=b''
  while not code.endswith(b'\\n'):
   b=os.read(0,1)
   if not b:break
   code+=b
  code=code.decode().strip()
 print('paired' if code=='123456' else 'pairing failed')
 sys.exit(0 if code=='123456' else 1)
if args[0]=='connect':print('connected' if args[1].endswith(':37123') else 'cannot connect');sys.exit(0)
if args[-1]=='get-state':
 ok=args[1].endswith(':37123')
 print('device' if ok else 'unauthorized');sys.exit(0 if ok else 1)
sys.exit(2)
'''
with tempfile.TemporaryDirectory(prefix='stella launcher tests ') as folder:
    temp = Path(folder)
    log = temp / 'calls.jsonl'
    for name in ('adb', 'scrcpy', 'adb.exe', 'scrcpy.exe'):
        p = temp / name
        p.write_text(mock)
        p.chmod(0o755)
    shutil.copy(root/'tools/windows/start-stellashell.ps1', temp)
    config = json.loads((root/'tools/windows/stellashell.example.json').read_text())
    config['scrcpy']['rightAltAsMeta'] = False
    (temp/'stellashell.example.json').write_text(json.dumps(config))
    env = dict(os.environ, ADB=str(temp/'adb'), SCRCPY=str(temp/'scrcpy'), MOCK_LOG=str(log))

    def run(kind, name, address='', answers='', success=True, extra=(), paired=False):
        log.write_text('')
        if kind == 'bash':
            command = ['bash', str(root/'tools/unix/start-stellashell.sh')]
            if address:
                command += ['--address', address]
        else:
            config['devices']['xperia']['address'] = address
            (temp/'stellashell.json').write_text(json.dumps(config))
            command = [opts.pwsh, '-NoProfile', '-File', str(temp/'start-stellashell.ps1')]
        run_env = dict(env)
        if kind == 'powershell':
            # Read-Host may buffer redirected stdin before a native child gets it.
            # Mock adb's interactive code prompt separately; actual pairing is not tested.
            run_env['MOCK_PAIR_CODE'] = '000000' if name=='pair failure retry' else '123456'
            answers = answers.replace('123456\n','').replace('000000\n','')
        result = subprocess.run(command+list(extra), input=answers, text=True, capture_output=True, env=run_env, timeout=25)
        calls = [json.loads(x) for x in log.read_text().splitlines()]
        launches = [args for binary,args in calls if binary == 'scrcpy']
        assert (result.returncode == 0) == success, (kind,name,result.stdout,result.stderr)
        assert len(launches) == int(success), (kind,name,calls)
        if success:
            assert launches[0][:2] == ['-s', '192.168.1.10:37123'], launches
            assert '--no-vd-destroy-content' in launches[0]
        assert not any('tcpip' in args or 'kill-server' in args for _,args in calls)
        assert any(args[0]=='pair' for _,args in calls) == paired
        assert '123456' not in log.read_text()
        print('PASS',kind,name)

    for kind in ['bash'] + (['powershell'] if opts.pwsh else []):
        batch = '--non-interactive' if kind=='bash' else '-NonInteractive'
        setup = '--setup' if kind=='bash' else '-Setup'
        run(kind,'existing connection',address='192.168.1.10:37123',extra=[batch])
        run(kind,'pair then separate connection port',answers='1\n192.168.1.10:40001\n123456\n192.168.1.10:37123\nn\n',paired=True)
        run(kind,'stale port / already paired',address='192.168.1.10:5555',answers='2\n192.168.1.10:37123\nn\n')
        run(kind,'pair failure retry',answers='1\n192.168.1.10:40001\n000000\n2\n192.168.1.10:37123\nn\n',paired=True)
        run(kind,'invalid endpoint retry',answers='2\n-bad:80\n192.168.1.10:65536\n192.168.1.10:37123\nn\n')
        run(kind,'batch failure',address='192.168.1.10:5555',extra=[batch],success=False)
        run(kind,'cancel',answers='q\n',success=False)
        run(kind,'forced setup',address='192.168.1.10:37123',answers='q\n',extra=[setup],success=False)

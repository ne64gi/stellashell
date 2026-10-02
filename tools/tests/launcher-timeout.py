#!/usr/bin/env python3
"""Real watchdog process cleanup, with fake ADB/timers and no device access."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / 'tools/unix/start-stellashell.sh').read_text()
function = source[source.index('bounded_adb() {'):source.index('\nconnect_endpoint()')]
with tempfile.TemporaryDirectory(prefix='stella timeout test ') as directory:
    folder = Path(directory)
    timer = folder / 'timer.pid'
    for name, content in {
        'sleep': '''#!/usr/bin/env python3
import os,time
from pathlib import Path
Path(os.environ['TIMER_PID']).write_text(str(os.getpid()))
time.sleep(.15 if os.environ['CASE']=='timeout' else 60)
''',
        'adb': '''#!/usr/bin/env python3
import os,time
from pathlib import Path
until=time.monotonic()+3
while not Path(os.environ['TIMER_PID']).exists() and time.monotonic()<until: time.sleep(.01)
if os.environ['CASE']=='timeout': time.sleep(60)
'''
    }.items():
        path = folder / name
        path.write_text(content)
        path.chmod(0o755)
    for case in ('fast', 'timeout'):
        timer.unlink(missing_ok=True)
        env = dict(os.environ, PATH=str(folder)+os.pathsep+os.environ['PATH'],
                   TIMER_PID=str(timer), CASE=case, adb=str(folder/'adb'))
        result = subprocess.run(['bash', '-c', function+'\nbounded_adb get-state\n'],
                                env=env, capture_output=True, text=True, timeout=5)
        assert (result.returncode == 0) == (case == 'fast'), (case, result)
        pid = int(timer.read_text())
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            pass
        else:
            raise AssertionError(f'{case}: timer {pid} survived completed request')
        print('PASS watchdog', case, 'timer reaped')

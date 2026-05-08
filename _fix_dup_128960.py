"""Truncate 3 duplicate ship #128960-#128965 blocks from design.css and mirror.
Each block is exactly 6653 bytes long, appended 4 times (1 wanted, 3 dups).
Atomic: write to temp + os.replace."""
import os, sys, tempfile

DUP_SIZE = 6653
DUPS_TO_REMOVE = 1  # 2 copies present; remove 1
TARGETS = [
    r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css",
    r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css",
]

for path in TARGETS:
    if not os.path.exists(path):
        continue
    sz = os.path.getsize(path)
    new_sz = sz - DUP_SIZE * DUPS_TO_REMOVE
    print(f'{path}: sz={sz} new_sz={new_sz}')
    # Sanity check: must contain exactly 4 string occurrences = 2 block copies
    with open(path, 'rb') as f:
        data = f.read()
    cnt = data.count(b'ship #128960')
    if cnt != 4:
        print(f'  expected 4 string occurrences (= 2 block copies), found {cnt} - SKIP')
        continue
    # Truncate by keeping bytes [0 : new_sz]
    truncated = data[:new_sz]
    cnt2 = truncated.count(b'ship #128960')
    if cnt2 != 2:
        print(f'  after-trunc would have {cnt2} marker occurrences (expected 2 = 1 block) - REFUSE')
        continue
    if b'END CSFLOAT-1:1 PARITY ship #128965' not in truncated[-7000:]:
        print(f'  END marker missing from kept tail - REFUSE')
        continue
    # Atomic write
    tmpfd, tmpname = tempfile.mkstemp(suffix='.fix', dir=os.path.dirname(path))
    try:
        with os.fdopen(tmpfd, 'wb') as out:
            out.write(truncated)
        os.replace(tmpname, path)
        print(f'  TRUNCATED -> sz now {os.path.getsize(path)}, markers={truncated.count(b"ship #128960")}')
    except Exception as e:
        if os.path.exists(tmpname):
            try: os.remove(tmpname)
            except: pass
        raise

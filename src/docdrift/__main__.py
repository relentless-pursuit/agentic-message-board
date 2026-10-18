"""Run DocDrift with: python3 src/docdrift <command>   (see: python3 src/docdrift --help)"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from docdrift.cli import main  # noqa: E402

sys.exit(main())

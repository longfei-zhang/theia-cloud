#!/usr/bin/env python

import argparse
import subprocess
import logging

debugLogging = True

logging.basicConfig()

logger = logging.getLogger('git-init')
if debugLogging:
    logger.setLevel(logging.DEBUG)
else:
    logger.setLevel(logging.INFO)

def runProcess(args):
    process = subprocess.Popen(
        args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout, stderr = process.communicate()
    out = stdout.decode('ascii')
    if len(out) > 0:
        logger.info(out)
    if process.returncode != 0:
        logger.error(stderr.decode('ascii'))
    return process.returncode

parser = argparse.ArgumentParser()
parser.add_argument("repository", help="The repository URL", type=str)
parser.add_argument("directory", help="The directory to clone into", type=str)
args = parser.parse_args()

code = runProcess(["git", "config", "--global", "credential.helper", "store"])
if code != 0:
    exit(code)

code = runProcess(["git", "clone", args.repository, args.directory])
if code != 0:
    exit(code)

if debugLogging:
    runProcess(["ls", "-al", args.directory])

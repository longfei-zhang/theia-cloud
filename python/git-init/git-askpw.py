#!/usr/bin/env python
import os
import logging

debugLogging = False
path = "/tmp/theia-cloud-askpw"

logging.basicConfig()

logger = logging.getLogger('ask-pw')
if debugLogging:
    logger.setLevel(logging.DEBUG)
else:
    logger.setLevel(logging.INFO)

if os.path.isfile(path):
    logger.debug("Prompt 2")
    prompt2 = os.environ['GIT_PROMPT2']
    print(prompt2)
else:
    logger.debug("Prompt 1")
    prompt1 = os.environ['GIT_PROMPT1']
    print(prompt1)
    os.mknod(path)

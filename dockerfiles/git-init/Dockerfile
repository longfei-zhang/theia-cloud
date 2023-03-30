FROM debian:11-slim

RUN apt update && \
    apt install python git -y && \
    apt clean

WORKDIR /tmp
COPY python/git-init/git-init.py .
COPY python/git-init/git-askpw.py .

ENV GIT_PROMPT1 "foo"
ENV GIT_PROMPT2 "bar"
ENV GIT_ASKPASS=/tmp/git-askpw.py

ENTRYPOINT [ "python", "git-init.py" ]
CMD ["-h"]
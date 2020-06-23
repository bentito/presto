#!/bin/bash -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

: "${REPO_DIR:?}"
: "${SUB_MGR_FILE:?}"
: "${IMAGE_TAG:=latest}"

REPO_MNT="$REPO_DIR:/etc/yum.repos.d"
SUB_MGR_MNT="$SUB_MGR_FILE:/etc/yum/pluginconf.d/subscription-manager.conf"
imagebuilder \
    -mount "$REPO_MNT" \
    -mount "$SUB_MGR_MNT" \
    -t "registry.access.redhat.com/openshift/ose-metering-presto:$IMAGE_TAG" \
    -f "$DIR/Dockerfile.rhel" \
    "$DIR"

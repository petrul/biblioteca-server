#!/bin/sh
# Derby has NO authentication by default, unlike MySQL's mandatory root
# password - generating derby.properties at startup from DB_USER/
# DB_PASSWORD (rather than baking a fixed user into the image) is what
# actually closes that gap. requireAuthentication + a single
# fullAccessUsers entry + defaultConnectionMode=noAccess means the one
# configured user is the only account that can connect or create/access
# a database at all.
set -eu

: "${DB_USER:?DB_USER is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

cat > /var/lib/derby/derby.properties <<EOF
derby.connection.requireAuthentication=true
derby.authentication.provider=BUILTIN
derby.user.${DB_USER}=${DB_PASSWORD}
derby.database.sqlAuthorization=true
derby.database.defaultConnectionMode=noAccess
derby.database.fullAccessUsers=${DB_USER}
EOF

# -h 0.0.0.0: Derby Network Server's own default is localhost-only, which
# would be unreachable from sibling containers (biblioteca-server)  -
# must be explicit.
exec java -Dderby.system.home=/var/lib/derby \
    -cp "/opt/derby/*" \
    org.apache.derby.drda.NetworkServerControl start -h 0.0.0.0

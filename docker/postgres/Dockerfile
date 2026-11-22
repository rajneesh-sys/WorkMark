FROM postgres:17-alpine

COPY init-app-user.sh /docker-entrypoint-initdb.d/10-create-app-user.sh
COPY init-app-user.sql /usr/local/share/init-app-user.sql
RUN chmod 0555 /docker-entrypoint-initdb.d/10-create-app-user.sh

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    apache2 apache2-utils git openssh-server openssh-client \
    && rm -rf /var/lib/apt/lists/* \
    && a2dismod mpm_event cgid >/dev/null 2>&1 || true \
    && a2enmod mpm_prefork cgi auth_basic authn_file authz_core authz_user env alias \
    && mkdir -p /run/sshd /srv/git \
    && ssh-keygen -A \
    && useradd -m -s /bin/bash gituser \
    && mkdir -p /home/gituser/.ssh \
    && ln -s /usr/lib/git-core/git-upload-pack /usr/local/bin/git-upload-pack \
    && ln -s /usr/lib/git-core/git-receive-pack /usr/local/bin/git-receive-pack \
    && mkdir -p /var/www \
    && printf '[safe]\n\tdirectory = *\n' > /var/www/.gitconfig \
    && chown www-data:www-data /var/www/.gitconfig
COPY httpd-git.conf /etc/apache2/conf-available/git.conf
RUN a2enconf git
EXPOSE 80 2222
CMD ["/bin/bash", "-c", "/usr/sbin/sshd -D -e -p 2222 & exec apache2ctl -DFOREGROUND"]

1. Добавление ./inputrc

```
"\e[A": history-search-backward
"\e[B": history-search-forward
```

2. Установить Docker

`https://docs.docker.com/engine/install/ubuntu/`

```
sudo usermod -aG docker $USER
newgrp docker
```

3. Установить Docker Compose

```
wget https://github.com/docker/compose/releases/download/1.29.2/docker-compose-Linux-x86_64
sudo chmod +x docker-compose-Linux-x86_64
sudo mv docker-compose-Linux-x86_64 /usr/local/bin/docker-compose

```

4. Установить Yandex Cloud CLI

```
curl -sSL https://storage.yandexcloud.net/yandexcloud-yc/install.sh | bash
yc config profile create yc-lct
yc config set cloud-id <cloud-id>
yc config set folder-id <folder-id>
yc container registry configure-docker
```

5. Клонирование репозитория

```
git clone ssh://ssh.sourcecraft.dev/lct-hackaton-2026/case-02-heat-network-routing-team-17.git
mv case-02-heat-network-routing-team-17/ dit
```

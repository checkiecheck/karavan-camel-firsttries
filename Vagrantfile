Vagrant.configure("2") do |config|
  config.vm.box = "bento/ubuntu-24.04"
  config.vm.network "private_network", ip: "192.168.56.10"
  config.vm.disk :disk, name: "primary", size: "180GB"
  
  config.vm.provider "virtualbox" do |vb|
    vb.memory = "12288" # <-- Geoptimaliseerd naar 12GB (Ollama draait immers op je Ryzen host!)
    vb.cpus = 6 
    vb.name = "Municipal-Integration-Pure-CamelK-Box"
  end

  config.vm.provision "shell", inline: <<-SHELL
    set -e

    # FIX 1: Systeemschijf (LVM) direct opschalen naar de volledige 180GB om DiskPressure taints te voorkomen
    if command -v growpart >/dev/null 2>&1; then
      echo "=== Systeemschijf (LVM) opschalen naar volledige capaciteit... ==="
      growpart /dev/sda 3 || true
      pvresize /dev/sda3 || true
      lvextend -l +100%FREE /dev/mapper/ubuntu--vg-ubuntu--lv || true
      resize2fs /dev/mapper/ubuntu--vg-ubuntu--lv || true
    fi

    # 1. Docker + Plugins installeren via de officiële stabiele APT-repository
    if ! command -v docker >/dev/null 2>&1; then
      echo "=== Systeem voorbereiden en Docker GPG-sleutel ophalen... ==="
      apt-get update -y
      apt-get install -y ca-certificates curl gnupg git jq

      install -m 0755 -d /etc/apt/keyrings
      curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg
      chmod a+r /etc/apt/keyrings/docker.gpg

      echo "=== Docker repository toevoegen aan APT sources... ==="
      echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null

      echo "=== Docker CE en Compose Plugin installeren... ==="
      apt-get update -y
      apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    fi

usermod -aG docker vagrant || true
    systemctl enable --now docker

    # =========================================================================
    # FIX: DOCKER DAEMON CONFIGURATIE (DNS + INSECURE REGISTRY BYPASS)
    # =========================================================================
    echo "=== Docker daemon configureren voor lokale registry... ==="
    mkdir -p /etc/docker
    cat <<EOF > /etc/docker/daemon.json
{
  "insecure-registries": ["192.168.56.10:5555", "localhost:5555"],
  "dns": ["8.8.8.8", "1.1.1.1"]
}
EOF
    # Herstart Docker om de nieuwe regels direct actief te maken
    systemctl restart docker
    # =========================================================================

    # Mappenstructuur netjes opzetten op de VM
    install -d -o vagrant -g vagrant /home/vagrant/demo
    install -d -o vagrant -g vagrant /home/vagrant/demo/workspace
    install -d -o vagrant -g vagrant /home/vagrant/demo/openhands_config
    cd /home/vagrant/demo

    # FIX: Portainer admin wachtwoordbestand vooraf aanmaken (minimaal 12 tekens!)
    echo "MunicipalAdminPassword123!" > /home/vagrant/demo/portainer_password.txt
    chown vagrant:vagrant /home/vagrant/demo/portainer_password.txt

    # OpenHands pre-configuration aanmaken om de UI popup te skippen
    cat <<'EOF' > /home/vagrant/demo/openhands_config/config.toml
[llm]
model = "ollama/qwen3-coder:30b"
base_url = "http://192.168.1.81:11434"
api_key = "none"
EOF

    cat <<'EOF' > /home/vagrant/demo/openhands_config/agent_settings.json
{
  "llm": {
    "model": "ollama/qwen3-coder:30b",
    "base_url": "http://192.168.1.81:11434",
    "api_key": "none"
  }
}
EOF
    chown -R vagrant:vagrant /home/vagrant/demo/openhands_config

    # 2. De complete Docker Compose stack
    cat <<'EOF' > docker-compose.yaml
services:
  portainer:
    container_name: portainer
    image: portainer/portainer-ce:latest
    restart: unless-stopped
    command: --admin-password-file=/tmp/portainer_password.txt
    ports: ["9000:9000"]
    volumes: 
      - /var/run/docker.sock:/var/run/docker.sock
      - /home/vagrant/demo/portainer_password.txt:/tmp/portainer_password.txt
      - portainer-data:/data
    networks: [municipal]

  gitea:
    container_name: gitea
    image: gitea/gitea:1.23-rootless
    restart: unless-stopped
    environment:
      - GITEA__security__INSTALL_LOCK=true
      - GITEA__server__ROOT_URL=http://192.168.56.10:3000/
      - GITEA__server__HTTP_PORT=3000
    ports: ["3000:3000"]
    volumes:
      - gitea-data:/data
    networks: [municipal]

  karavan-db:
    container_name: karavan-db
    image: postgres:15-alpine
    restart: unless-stopped
    environment:
      - POSTGRES_USER=karavan
      - POSTGRES_PASSWORD=karavan
      - POSTGRES_DB=karavan
    volumes:
      - postgres-data:/var/lib/postgresql/data
    networks: [municipal]

  karavan:
    container_name: karavan
    image: ghcr.io/apache/camel-karavan:4.10.2
    restart: unless-stopped
    ports: ["8080:8080"]
    environment:
      - KARAVAN_GIT_REPOSITORY=http://gitea:3000/municipal-admin/integrations.git
      - KARAVAN_GIT_USERNAME=municipal-admin
      - KARAVAN_GIT_PASSWORD=MunicipalPassword123
      - KARAVAN_GIT_BRANCH=main
      - QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://karavan-db:5432/karavan
      - QUARKUS_DATASOURCE_USERNAME=karavan
      - QUARKUS_DATASOURCE_PASSWORD=karavan
      - DOCKER_HOST=unix:///var/run/docker.sock
      - KARAVAN_DOCKER_NETWORK=municipal
      - KARAVAN_CONTAINER_IMAGE_REGISTRY=192.168.56.10:5555
      - KARAVAN_CONTAINER_IMAGE_REGISTRY_USERNAME=
      - KARAVAN_CONTAINER_IMAGE_REGISTRY_PASSWORD=
      - KARAVAN_CONTAINER_IMAGE_GROUP=municipal
      - QUARKUS_LOG_LEVEL=INFO
      - QUARKUS_LOG_CONSOLE_JSON=true
      - QUARKUS_LOG_CONSOLE_JSON_PRETTY_PRINT=false
      - QUARKUS_LOG_CONSOLE_JSON_ADDITIONAL_FIELD__SERVICE__VALUE=karavan
      - JAVA_TOOL_OPTIONS=-Xmx512m -Xms256m
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    labels:
      - "org.apache.camel.karavan/type=internal"
    networks: [municipal]
    depends_on: [karavan-db, gitea]

  registry:
    container_name: registry
    image: registry:2
    restart: unless-stopped
    ports: ["5555:5000"]
    networks: [municipal]
    labels:
      - "org.apache.camel.karavan/type=internal"

  elasticsearch:
    container_name: elasticsearch
    image: docker.elastic.co/elasticsearch/elasticsearch:8.13.0
    restart: unless-stopped
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
      - "ES_JAVA_OPTS=-Xms512m -Xmx512m"
    ports: ["9200:9200"]
    volumes:
      - elastic-data:/usr/share/elasticsearch/data
    networks: [municipal]

  kibana:
    container_name: kibana
    image: docker.elastic.co/kibana/kibana:8.13.0
    restart: unless-stopped
    environment:
      - ELASTICSEARCH_HOSTS=http://elasticsearch:9200
    ports: ["5601:5601"]
    networks: [municipal]
    depends_on: [elasticsearch]
  
  filebeat:
    container_name: filebeat
    image: docker.elastic.co/beats/filebeat:8.13.0
    restart: unless-stopped
    user: root
    volumes:
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
    command: >
      filebeat -e
      -E output.elasticsearch.hosts=["http://elasticsearch:9200"]
      -E setup.ilm.enabled=false
      -E filebeat.inputs=[{type:container,paths:["/var/lib/docker/containers/*/*.log"]}]
    depends_on:
      - elasticsearch
    networks: [municipal]

  openhands:
    container_name: openhands
    image: ghcr.io/all-hands-ai/openhands:0.16
    restart: unless-stopped
    ports: ["3010:3000"]
    environment:
      - LLM_MODEL=ollama/qwen3-coder:30b
      - LLM_BASE_URL=http://192.168.1.81:11434
      - LLM_API_KEY=none
      - WORKSPACE_MOUNT_PATH=/home/vagrant/demo/workspace
      - ALLOW_INSECURE_GIT_ACCESS=true
      - SANDBOX_USER_ID=1000
      - DEBUG=1
      - SANDBOX_RUNTIME_CONTAINER_IMAGE=192.168.56.10:5555/municipal/openhands-camel-runtime:latest
      - SANDBOX_NETWORK=municipal
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - /usr/bin/docker:/usr/bin/docker
      - /usr/libexec/docker/cli-plugins:/usr/libexec/docker/cli-plugins
      - /home/vagrant/demo/workspace:/opt/workspace_base
      - /home/vagrant/demo/openhands_config:/home/openhands/.openhands
    networks: [municipal]
    extra_hosts:
      - "host.docker.internal:host-gateway"
      
volumes:
  portainer-data:
  gitea-data:
  postgres-data:
  elastic-data:

networks:
  municipal:
    name: municipal
EOF

    # 3. Infrastructuur voorbereiding & Core Opstarten
    sysctl -w vm.max_map_count=262144
    grep -q '^vm.max_map_count' /etc/sysctl.conf || echo 'vm.max_map_count=262144' >> /etc/sysctl.conf

    # Start als eerste Gitea en Postgres op om de API in te richten
    docker compose up -d gitea karavan-db

    echo "=== Wachten op Gitea API... ==="
    for i in $(seq 1 60); do
      if curl -fsS http://localhost:3000/api/v1/version >/dev/null 2>&1; then
        echo "Gitea is up."
        break
      fi
      sleep 3
    done

    # Inrichting Gitea admin user
    if ! docker exec -u git gitea gitea admin user list | grep -q '^.*municipal-admin'; then
      docker exec -u git gitea gitea admin user create \
        --admin \
        --username municipal-admin \
        --password MunicipalPassword123 \
        --email admin@local.dev \
        --must-change-password=false
    else
      echo "User 'municipal-admin' bestaat al, overslaan."
    fi

# Schone Git Repository aanmaken in Gitea voor code-opslag
    curl -s -o /dev/null -w "Gitea repo create HTTP %{http_code}\\n" \
      -X POST "http://localhost:3000/api/v1/user/repos" \
      -H "accept: application/json" \
      -H "Content-Type: application/json" \
      -u "municipal-admin:MunicipalPassword123" \
      -d '{"name":"integrations","private":false,"auto_init":true,"default_branch":"main"}'
    
    # =========================================================================
    # FIX: WORKSPACE KOPPELEN AAN GITEA (HET ONTBREKENDE SCHAKELTJE!)
    # =========================================================================
    echo "=== Workspace-map omtoveren tot actieve Gitea Git repository... ==="
    rm -rf /home/vagrant/demo/workspace
    
    # We clonen de zojuist gemaakte repo via localhost om de map te vullen
    git clone http://municipal-admin:MunicipalPassword123@localhost:3000/municipal-admin/integrations.git /home/vagrant/demo/workspace
    
    cd /home/vagrant/demo/workspace
    # Nu zetten we de Git URL om naar de INTERNE Docker-netwerknaam 'gitea' 
    # zodat de OpenHands-sandbox (die op het 'municipal' netwerk leeft) erbij kan!
    git remote set-url origin http://municipal-admin:MunicipalPassword123@gitea:3000/municipal-admin/integrations.git
    
    # Git identiteit alvast instellen zodat de AI direct mag committen zonder popups
    git config user.name "municipal-admin"
    git config user.email "admin@local.dev"
    chown -R vagrant:vagrant /home/vagrant/demo/workspace
    # =========================================================================

# =========================================================================
    # FIX: CUSTOM CAMEL-SANDBOX IMAGE BOUWEN VOOR OPENHANDS
    # =========================================================================
    echo "=== Custom Camel-Sandbox Image bouwen voor OpenHands... ==="
    mkdir -p /home/vagrant/demo/sandbox-build
    
    cat <<'EOF' > /home/vagrant/demo/sandbox-build/Dockerfile
FROM ghcr.io/all-hands-ai/runtime:0.16-nikolaik

USER root

# 1. Mappen aanmaken voor JBang én Maven cache, en de rechten volledig openzetten
RUN mkdir -p /opt/jbang /opt/m2 && chmod -R 777 /opt/jbang /opt/m2

# 2. Globale omgevingsvariabelen instellen zodat iedereen dezelfde mappen gebruikt
ENV JBANG_DIR=/opt/jbang
ENV JAVA_TOOL_OPTIONS="-Dmaven.repo.local=/opt/m2"

# 3. Installeer Java 17 (nodig voor Camel/JBang)
RUN apt-get update && apt-get install -y openjdk-17-jdk wget unzip && rm -rf /var/lib/apt/lists/*

# 4. Installeer JBang globaal
RUN wget https://github.com/jbangdev/jbang/releases/download/v0.116.0/jbang-0.116.0.zip -O /tmp/jbang.zip \
    && unzip /tmp/jbang.zip -d /opt/ \
    && ln -s /opt/jbang-0.116.0/bin/jbang /usr/local/bin/jbang \
    && rm /tmp/jbang.zip

# 5. Schakel NU alvast over naar de OpenHands sandbox gebruiker (UID 1000)
# Hierdoor worden de downloads met de juiste internetverbinding van de build uitgevoerd én opgeslagen
USER 1000

# 6. Vertrouw Apache en installeer de Camel CLI
RUN jbang trust add https://github.com/apache/ \
    && jbang app install --force camel@apache/camel

# 7. Pre-warm de cache VOLLEDIG (dit vult /opt/m2 met alle benodigde Camel JARs)
RUN /opt/jbang/bin/camel --version

# 8. Kort terug naar root om de universele symlink te leggen
USER root
RUN ln -s /opt/jbang/bin/camel /usr/local/bin/camel

# 9. Eindgids: Altijd netjes als de sandbox-gebruiker eindigen
USER 1000
EOF

    # Bouw de image en push hem direct naar je eigen lokale Docker Registry container!
    docker build -t 192.168.56.10:5555/municipal/openhands-camel-runtime:latest /home/vagrant/demo/sandbox-build
    docker push 192.168.56.10:5555/municipal/openhands-camel-runtime:latest
    # =========================================================================
    # Start nu de rest van de docker compose stack
    cd /home/vagrant/demo
    docker compose up -d

    # =========================================================================
    # 4. K3S (KUBERNETES) SETUP + CAMEL K OPERATOR ENGINE
    # =========================================================================
    echo "=== Starten met K3s (Lightweight Kubernetes) Installatie ==="
    curl -sfL https://get.k3s.io | sh -

    mkdir -p /home/vagrant/.kube
    cp /etc/rancher/k3s/k3s.yaml /home/vagrant/.kube/config
    chown -R vagrant:vagrant /home/vagrant/.kube
    export KUBECONFIG=/home/vagrant/.kube/config

    echo "Wachten op K3s cluster..."
    until kubectl get nodes >/dev/null 2>&1; do sleep 2; done

    # K3s koppelen aan de Docker Compose Registry container op poort 5555
    echo "=== K3s configureren voor lokale Docker Registry mirror ==="
    mkdir -p /etc/rancher/k3s
    cat <<EOF > /etc/rancher/k3s/registries.yaml
mirrors:
  "192.168.56.10:5555":
    endpoint:
      - "http://192.168.56.10:5555"
EOF
    systemctl restart k3s
    
    # FIX 5: Wachten tot de node écht de status "Ready" heeft (ipv alleen API-respons) om ghost taints te vermijden
    echo "=== Wachten tot K3s node de status Ready heeft... ==="
    until kubectl get nodes | grep -q "Ready"; do sleep 2; done

    echo "=== Camel K CLI (kamel) installeren ==="
    CAMEL_K_VERSION="2.5.0"
    curl -Lf "https://github.com/apache/camel-k/releases/download/v${CAMEL_K_VERSION}/camel-k-client-${CAMEL_K_VERSION}-linux-amd64.tar.gz" | tar -xzf - -C /usr/local/bin kamel
    chmod +x /usr/local/bin/kamel

    # FIX 6: "--wait" verwijderd om te voorkomen dat het Vagrant-script oneindig blijft hangen bij een trage startup
    echo "=== Camel K Operator uitrollen in K3s... ==="
    kamel install --olm=false \
      --registry 192.168.56.10:5555 \
      --registry-insecure true \
      --force

# FIX 7: Automatische CPU/RAM ontheffing meegeven aan álle toekomstige Camel K integraties om OOM-crashes te tackelen
    echo "=== Standaard limieten instellen voor Camel K integraties... ==="
    until kubectl get integrationplatform camel-k >/dev/null 2>&1; do sleep 2; done
    sleep 5 # <-- Extra adempauze zodat de operator klaar is met zijn eerste schrijfactie

    # Retry-loop om de race condition/optimistic locking te tackelen
    for i in {1..5}; do
      echo "Poging $i om Camel K limieten toe te passen..."
      if kubectl patch integrationplatform camel-k --type=json -p='[
        {"op": "add", "path": "/spec/traits/container", "value": {"limitMemory": "1Gi", "requestMemory": "512Mi"}}
      ]'; then
        echo "=== Limieten succesvol toegepast! ==="
        break
      else
        echo "Conflict gedetecteerd (resource gewijzigd). Volgende poging over 3 seconden..."
        sleep 3
      fi
    done

    echo "==========================================================="
    echo " HYBRIDE ARCHITECTUUR VOLLEDIG OPERATIONEEL!"
    echo " - Portainer:     http://192.168.56.10:9000"
    echo " - Gitea (Git):   http://192.168.56.10:3000"
    echo " - Karavan:       http://192.168.56.10:8080"
    echo " - Kibana (Logs): http://192.168.56.10:5601"
    echo " - OpenHands (AI):http://192.168.56.10:3010"
    echo "==========================================================="
  SHELL
end

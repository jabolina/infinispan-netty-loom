#!/usr/bin/env bash
set -e
cd "$(dirname "${BASH_SOURCE[0]}")"

REPO_RAW="https://raw.githubusercontent.com/jgroups-extras/IspnPerfTest/master/ansible"

clean() {
    echo "Cleaning up generated files..."
    rm -f aws-ec2.yml
    rm -rf roles/
    rm -rf jbang-catalog/
    echo "Clean complete."
}

setup() {
    # --- AWS EC2 provisioning role ---
    if [ ! -f roles/aws_ec2/tasks/main.yml ]; then
        echo "Fetching AWS EC2 provisioning role..."
        mkdir -p roles/aws_ec2/{defaults,tasks,templates}

        curl -sL "$REPO_RAW/aws-ec2.yml" -o aws-ec2.yml
        curl -sL "$REPO_RAW/roles/ec2-requirements.yml" -o roles/ec2-requirements.yml
        curl -sL "$REPO_RAW/roles/aws_ec2/defaults/main.yml" -o roles/aws_ec2/defaults/main.yml

        for f in main.yml create-resources.yml delete-resources.yml; do
            curl -sL "$REPO_RAW/roles/aws_ec2/tasks/$f" -o "roles/aws_ec2/tasks/$f"
        done

        curl -sL "$REPO_RAW/roles/aws_ec2/templates/inventory.yaml.j2" \
            -o roles/aws_ec2/templates/inventory.yaml.j2

        # Rename instance tag prefix
        sed -i 's/ispn_perf_/ispn_loom_/g' roles/aws_ec2/tasks/create-resources.yml

        echo "AWS EC2 role ready."
    else
        echo "AWS EC2 role already present, skipping."
    fi

    # --- Hyperfoil jbang-catalog ---
    if [ ! -d "jbang-catalog" ]; then
        echo "Cloning jbang-catalog (dlovison/hotrod)..."
        git clone -b hotrod https://github.com/diegolovison/jbang-catalog.git
    else
        echo "jbang-catalog already present, skipping clone."
    fi

    echo "Setup complete."
}

case "${1:-}" in
    -clean|--clean|clean)
        clean
        ;;
    "")
        setup
        ;;
    *)
        echo "Usage: $(basename "$0") [--clean]"
        exit 1
        ;;
esac

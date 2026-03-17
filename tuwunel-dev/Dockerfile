# Build Tuwunel from source with patched ruma (timeline_limit fix)
FROM rust:1.94.0-bookworm AS builder

# Install build dependencies
RUN apt-get update && apt-get install -y \
    libclang-dev \
    cmake \
    pkg-config \
    libssl-dev \
    liburing-dev \
    && rm -rf /var/lib/apt/lists/*

# Clone Tuwunel source
WORKDIR /build
RUN git clone --depth=1 https://github.com/matrix-construct/tuwunel.git .

# Patch Cargo.toml to use our ruma fork with the timeline_limit fix
RUN sed -i 's|git = "https://github.com/matrix-construct/ruma"|git = "https://github.com/ltoenjes/ruma"|' Cargo.toml \
    && sed -i 's|rev = "30d063c4503c3b630cdd55eda71a0bc3504518a2"|rev = "45ff72e486a14d7224a426414c47fa66f25a4f05"|' Cargo.toml

# Update Cargo.lock to reflect the new ruma dependency
RUN cargo update -p ruma

# Build release binary
RUN cargo build --release --bin tuwunel

# Runtime image
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y \
    ca-certificates \
    liburing2 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/release/tuwunel /usr/local/bin/tuwunel

# Create data directory
RUN mkdir -p /var/lib/tuwunel

ENTRYPOINT ["/usr/local/bin/tuwunel"]

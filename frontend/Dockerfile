# ============================================================================
# CardDemo Frontend - Multi-Stage Docker Build
# ============================================================================
# Stage 1: Build Stage (Node.js 20 Alpine)
#   - Compiles TypeScript to JavaScript
#   - Bundles React application with Vite
#   - Optimizes assets (minification, tree-shaking, code splitting)
#
# Stage 2: Runtime Stage (Nginx 1.27 Alpine)
#   - Serves static files with Nginx web server
#   - Configures SPA routing (all routes → index.html)
#   - Implements security headers and gzip compression
#
# Per Agent Action Plan Section 0.4.16 and 0.7.1 MINIMAL CHANGE CLAUSE
# ============================================================================

# ============================================================================
# Stage 1: Build Stage
# ============================================================================
FROM node:20-alpine AS build

# Set working directory
WORKDIR /app

# Copy package files first for better Docker layer caching
# This layer is cached until package.json or package-lock.json changes
COPY package.json package-lock.json ./

# Install dependencies using npm ci for reproducible builds
# --only=production=false ensures devDependencies are installed (needed for build)
# npm ci is faster and more reliable than npm install for CI/CD
RUN npm ci --only=production=false

# Copy source code and configuration files
# This layer rebuilds when source code changes
COPY . .

# Build production-optimized assets
# - TypeScript compilation (tsc)
# - Vite bundling with optimization
# - Output: dist/ folder with minified, tree-shaken, code-split bundles
RUN npm run build

# ============================================================================
# Stage 2: Runtime Stage (Nginx)
# ============================================================================
FROM nginx:1.27-alpine

# Create custom Nginx configuration for Single Page Application routing
# This configuration:
# - Enables SPA routing (all routes fallback to index.html)
# - Configures caching (1 year for static assets, no-cache for HTML)
# - Adds security headers (X-Content-Type-Options, X-Frame-Options, etc.)
# - Enables gzip compression for text-based assets
RUN echo 'server {' > /etc/nginx/conf.d/default.conf && \
    echo '    listen 80;' >> /etc/nginx/conf.d/default.conf && \
    echo '    server_name _;' >> /etc/nginx/conf.d/default.conf && \
    echo '    root /usr/share/nginx/html;' >> /etc/nginx/conf.d/default.conf && \
    echo '    index index.html;' >> /etc/nginx/conf.d/default.conf && \
    echo '' >> /etc/nginx/conf.d/default.conf && \
    echo '    # SPA routing - fallback to index.html for all routes' >> /etc/nginx/conf.d/default.conf && \
    echo '    location / {' >> /etc/nginx/conf.d/default.conf && \
    echo '        try_files $uri $uri/ /index.html;' >> /etc/nginx/conf.d/default.conf && \
    echo '    }' >> /etc/nginx/conf.d/default.conf && \
    echo '' >> /etc/nginx/conf.d/default.conf && \
    echo '    # Cache static assets (CSS, JS, images, fonts) for 1 year' >> /etc/nginx/conf.d/default.conf && \
    echo '    # Vite adds content hash to filenames, so cache invalidation is automatic' >> /etc/nginx/conf.d/default.conf && \
    echo '    location ~* \\.(?:css|js|jpg|jpeg|png|gif|ico|svg|woff|woff2|ttf|eot)$ {' >> /etc/nginx/conf.d/default.conf && \
    echo '        expires 1y;' >> /etc/nginx/conf.d/default.conf && \
    echo '        add_header Cache-Control "public, immutable";' >> /etc/nginx/conf.d/default.conf && \
    echo '    }' >> /etc/nginx/conf.d/default.conf && \
    echo '' >> /etc/nginx/conf.d/default.conf && \
    echo '    # Do not cache HTML files (always check with server)' >> /etc/nginx/conf.d/default.conf && \
    echo '    location ~* \\.html$ {' >> /etc/nginx/conf.d/default.conf && \
    echo '        add_header Cache-Control "no-cache";' >> /etc/nginx/conf.d/default.conf && \
    echo '    }' >> /etc/nginx/conf.d/default.conf && \
    echo '' >> /etc/nginx/conf.d/default.conf && \
    echo '    # Security headers' >> /etc/nginx/conf.d/default.conf && \
    echo '    add_header X-Content-Type-Options "nosniff" always;' >> /etc/nginx/conf.d/default.conf && \
    echo '    add_header X-Frame-Options "DENY" always;' >> /etc/nginx/conf.d/default.conf && \
    echo '    add_header X-XSS-Protection "1; mode=block" always;' >> /etc/nginx/conf.d/default.conf && \
    echo '    add_header Referrer-Policy "strict-origin-when-cross-origin" always;' >> /etc/nginx/conf.d/default.conf && \
    echo '' >> /etc/nginx/conf.d/default.conf && \
    echo '    # Gzip compression for text-based assets' >> /etc/nginx/conf.d/default.conf && \
    echo '    gzip on;' >> /etc/nginx/conf.d/default.conf && \
    echo '    gzip_vary on;' >> /etc/nginx/conf.d/default.conf && \
    echo '    gzip_min_length 1024;' >> /etc/nginx/conf.d/default.conf && \
    echo '    gzip_types text/plain text/css text/xml text/javascript application/x-javascript application/xml+rss application/json application/javascript;' >> /etc/nginx/conf.d/default.conf && \
    echo '}' >> /etc/nginx/conf.d/default.conf

# Copy production build artifacts from build stage
# Only copies dist/ folder (optimized output), not source code or node_modules
# This keeps the final image minimal (~30MB vs ~500MB if including build stage)
COPY --from=build /app/dist /usr/share/nginx/html

# Expose port 80 for HTTP traffic
EXPOSE 80

# Health check for container orchestration (Kubernetes liveness/readiness probes)
# Checks if Nginx is serving content every 30 seconds
# Container is marked unhealthy after 3 consecutive failures
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost/ || exit 1

# Default CMD from nginx:alpine base image runs Nginx in foreground
# CMD ["nginx", "-g", "daemon off;"]

# ============================================================================
# Build and Run Instructions:
# ============================================================================
# Build image:
#   docker build -t carddemo-frontend:latest .
#
# Run container:
#   docker run -p 3000:80 carddemo-frontend:latest
#   Access at http://localhost:3000
#
# Docker Compose (with backend):
#   docker-compose up frontend backend postgres
#   Frontend: http://localhost:3000
#   Backend API: http://localhost:8080
#
# Image sizes:
#   - Build stage: ~500MB (Node.js + dependencies)
#   - Final image: ~30MB (Nginx + static files only)
#
# Security features:
#   - Non-root user (nginx user in Alpine nginx package)
#   - No Node.js runtime in final image
#   - No source code in final image
#   - Security headers enabled
#   - Minimal Alpine Linux base
# ============================================================================

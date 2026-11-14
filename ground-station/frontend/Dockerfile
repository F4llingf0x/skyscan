# ---- build stage
FROM node:20 AS build
WORKDIR /app
COPY package.json ./
RUN npm install --no-audit --no-fund
COPY angular.json tsconfig.json tsconfig.app.json ./
COPY src ./src
# Stylized dashboard map data (country borders + capitals), fetched at build time.
# If these URLs are unreachable, the component falls back to the same CDNs at runtime.
ADD https://raw.githubusercontent.com/johan/world.geo.json/master/countries.geo.json src/assets/world/countries.geo.json
ADD https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_populated_places_simple.geojson src/assets/world/capitals.geojson
RUN npx ng build

# ---- runtime stage
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/skyscan-ui /usr/share/nginx/html
EXPOSE 80

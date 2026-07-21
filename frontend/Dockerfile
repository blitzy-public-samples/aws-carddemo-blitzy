# CardDemo Frontend — Next.js + Material UI image (Node 22; satisfies I3 22.x >= 22.12.0).
# Application source (src/app/**) is created by implementation agents; this image is ready
# to build once that source exists.
FROM node:22-alpine

WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm install

COPY . .

EXPOSE 3000

# Once source exists, production build is: `npm run build` then `npm start`.
CMD ["npm", "run", "dev"]

#!/usr/bin/env sh
# Wraps ui/app.html (an HTML fragment, also publishable as a standalone demo) into the page Spring Boot serves.
set -e
cd "$(dirname "$0")/.."
{
  printf '<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n</head>\n<body>\n'
  cat ui/app.html
  printf '\n</body>\n</html>\n'
} > src/main/resources/static/index.html
echo "Wrote src/main/resources/static/index.html"

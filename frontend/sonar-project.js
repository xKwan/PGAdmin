// SonarQube analysis of the frontend (plan section 8.3). Run from frontend\ after `npm run test:coverage`.
// SONAR_HOST_URL and SONAR_TOKEN come from the environment (CI variables, or scripts\sonar\Start-LocalSonar.ps1).
import { scan } from '@sonar/scan';

await scan({
  serverUrl: process.env.SONAR_HOST_URL || 'http://localhost:9000',
  token: process.env.SONAR_TOKEN,
  options: {
    'sonar.projectKey': 'pgweb-frontend',
    'sonar.projectName': 'pgweb frontend',
    'sonar.sources': 'src,scripts',
    'sonar.tests': 'src,scripts',
    'sonar.test.inclusions': '**/*.test.js,**/*.test.jsx,src/test/**,src/mocks/**',
    'sonar.exclusions': 'src/test/**,src/mocks/**,**/*.test.js,**/*.test.jsx',
    'sonar.coverage.exclusions': 'src/main.jsx,scripts/**',
    'sonar.javascript.lcov.reportPaths': 'coverage/lcov.info',
    'sonar.sourceEncoding': 'UTF-8',
    'sonar.qualitygate.wait': 'true',
    'sonar.qualitygate.timeout': '300',
  },
});

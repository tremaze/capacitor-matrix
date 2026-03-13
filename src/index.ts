import { registerPlugin } from '@capacitor/core';

import type { CapMatrixPlugin } from './definitions';

const CapMatrix = registerPlugin<CapMatrixPlugin>('CapMatrix', {
  web: () => import('./web').then((m) => new m.CapMatrixWeb()),
});

export * from './definitions';
export { CapMatrix };

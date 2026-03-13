import { WebPlugin } from '@capacitor/core';

import type { CapMatrixPlugin } from './definitions';

export class CapMatrixWeb extends WebPlugin implements CapMatrixPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}

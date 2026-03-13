export interface CapMatrixPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}

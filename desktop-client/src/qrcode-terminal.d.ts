declare module "qrcode-terminal" {
  export interface GenerateOptions {
    small?: boolean;
  }

  export function generate(input: string, options?: GenerateOptions): void;

  const qrcode: {
    generate: typeof generate;
  };

  export default qrcode;
}

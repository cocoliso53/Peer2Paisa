declare module '../dist/fsm-lib.js' {
  export function greet(name: string): string;
  export function processData(data: number[]): number[];
  export function fibonacci(n: number): number;
  export function readFileAsync(
    filePath: string, 
    callback: (err: Error | null, data: string | null) => void
  ): void;
  export function transformJson(jsonStr: string): string;
}
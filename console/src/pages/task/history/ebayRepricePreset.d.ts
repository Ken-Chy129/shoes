export interface EbayRepricePresetRecord {
    values: Record<string, any>;
    taskId: string | null;
    lastKnownServerTaskId: string | null;
}

export function isRememberedEbayRepriceTask(taskType: string): boolean;
export function compareTaskIds(left: string | number | null | undefined, right: string | number | null | undefined): number;
export function extractEbayRepriceFormValues(taskType: string, values: Record<string, any> | null | undefined): Record<string, any> | null;
export function parseEbayRepriceTaskParams(taskType: string, rawParams: unknown): Record<string, any> | null;
export function readEbayRepricePreset(taskType: string, storage?: Storage | null): EbayRepricePresetRecord | null;
export function saveEbayRepricePreset(
    taskType: string,
    values: Record<string, any>,
    meta?: { taskId?: string | number | null; lastKnownServerTaskId?: string | number | null },
    storage?: Storage | null,
): EbayRepricePresetRecord | null;
export function shouldApplyServerEbayRepricePreset(
    localPreset: EbayRepricePresetRecord | null,
    serverTaskId: string | number | null | undefined,
): boolean;

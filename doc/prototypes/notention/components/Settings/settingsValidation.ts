// src/components/Settings/settingsValidation.ts
interface Settings {
    concurrency: number;
    apiKey: string;
    theme: string;
    modelName: string;
    temperature: number;
    usePersistence: boolean;
    serpApiKey: string;
}

interface ValidationResult {
    isValid: boolean;
    errors: { [key: string]: string };
}

export const validateSettings = (settings: Settings): ValidationResult => {
    let isValid = true;
    const errors: { [key: string]: string } = {};

    if (settings.concurrency < 1 || settings.concurrency > 10) {
        errors.concurrency = 'Concurrency must be between 1 and 10';
        isValid = false;
    }

    if (settings.temperature < 0 || settings.temperature > 1) {
        errors.temperature = 'Temperature must be between 0 and 1';
        isValid = false;
    }

    if (!settings.apiKey) {
        errors.apiKey = 'API Key is required';
        isValid = false;
    }

    return {isValid, errors};
};

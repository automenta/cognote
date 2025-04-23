// src/lib/settingsService.ts
interface Settings {
    concurrency: number;
    apiKey: string;
    theme: string;
    modelName: string;
    temperature: number;
    usePersistence: boolean;
    serpApiKey: string;
}

const defaultSettings: Settings = {
    concurrency: 5,
    apiKey: '',
    theme: '',
    modelName: 'gpt-3.5-turbo',
    temperature: 0.7,
    usePersistence: false,
    serpApiKey: '',
};

export const SettingsService = {
    getSettings: (): Settings => {
        try {
            const settingsString = localStorage.getItem('settings');
            if (settingsString) {
                return JSON.parse(settingsString) as Settings;
            }
            return defaultSettings;
        } catch (error) {
            console.error("Error getting settings from local storage:", error);
            return defaultSettings;
        }
    },
    saveSettings: (settings: Settings): void => {
        try {
            localStorage.setItem('settings', JSON.stringify(settings));
        } catch (error) {
            console.error("Error saving settings to local storage:", error);
        }
    },
};

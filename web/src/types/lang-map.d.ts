declare module 'lang-map' {
  interface LangMap {
    (): {
      extensions: Record<string, string[]>
      languages: Record<string, string[]>
    }
    extensions: (lang: string) => string[]
    languages: (extOrFilename: string) => string[]
  }

  const langMap: LangMap
  export default langMap
}

export interface ImageSearchResult {
  imageId: number
  uri: string
  sha256: string
  width: number | null
  height: number | null
  mimeType: string | null
  relevance: number
}

export interface ImageTagInfo {
  name: string
  confidence: number
}

export interface ImageTagsResult {
  imageId: number
  uri: string | null
  sha256: string | null
  aiTags: ImageTagInfo[]
  userTags: ImageTagInfo[]
}

export interface Statistics {
  totalImages: number
  pendingCount: number
  doneCount: number
  failedCount: number
}

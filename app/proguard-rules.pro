# R8 release evidence: BoofCV references Lombok's source-retention marker annotation,
# but the annotation is not packaged and is not needed at runtime.
-dontwarn lombok.Generated

# Encoder KLT uses FactoryConvolveDown.getMethod("horizontal"/"vertical")
# to create its normalized GrayU8 pyramid. Preserve only these reflected entry
# points (not all BoofCV); otherwise minified release cannot initialize tracking.
-keepclassmembers,allowoptimization class boofcv.alg.filter.convolve.ConvolveImageDownNormalized {
    public static void horizontal(boofcv.struct.convolve.Kernel1D_S32, boofcv.struct.image.GrayU8, boofcv.struct.image.GrayI8, int);
    public static void vertical(boofcv.struct.convolve.Kernel1D_S32, boofcv.struct.image.GrayU8, boofcv.struct.image.GrayI8, int);
}

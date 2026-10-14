package com.debanshu777.caraml.core.data.inference

class DiffusionMemoryException : IllegalStateException(
    "There is not enough available memory for this generation. Try a smaller output."
)

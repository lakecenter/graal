/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.reflect.target;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.Map;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Inject;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RecomputeFieldValue.Kind;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.reflect.hosted.ReflectionObjectReplacer;

@TargetClass(value = Executable.class)
public final class Target_java_lang_reflect_Executable {

    /**
     * The parameters field doesn't need a value recomputation. Its value is pre-loaded in the
     * {@link ReflectionObjectReplacer}.
     */
    @Alias @RecomputeFieldValue(kind = Kind.Reset)//
    Target_java_lang_reflect_Parameter[] parameters;

    @Alias @RecomputeFieldValue(kind = Kind.Reset)//
    boolean hasRealParameterData;

    /**
     * The declaredAnnotations field doesn't need a value recomputation. Its value is pre-loaded in
     * the {@link ReflectionObjectReplacer}.
     */
    @Alias @RecomputeFieldValue(kind = Kind.Reset)//
    Map<Class<? extends Annotation>, Annotation> declaredAnnotations;

    @Inject @RecomputeFieldValue(kind = Kind.Reset) //
    byte[] typeAnnotations;

    @Alias
    native Annotation[][] sharedGetParameterAnnotations(Class<?>[] parameterTypes, byte[] annotations);

    @Substitute
    private Target_java_lang_reflect_Parameter[] getParameters0() {
        return parameters;
    }

    @Substitute
    byte[] getTypeAnnotationBytes0() {
        return typeAnnotations;
    }
}

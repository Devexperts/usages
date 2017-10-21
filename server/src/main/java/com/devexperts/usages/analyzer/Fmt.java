/**
 * Copyright (C) 2017 Devexperts LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 */
package com.devexperts.usages.analyzer;

import java.nio.charset.Charset;

class Fmt {
    public static final Charset CHARSET = Charset.forName("UTF-8");

    public static final String COMMENT_PREFIX = "#";
    public static final String CLASS_PREFIX = "\t";
    public static final String MEMBER_PREFIX = "\t\t";
    public static final String USE_KINDS_PREFIX = " -- ";
    public static final String USE_KINDS_SEPARATOR = ",";
}

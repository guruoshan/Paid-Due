package com.dailymoneyregister;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ExpenseSupportingDocumentTestProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {

        if (uri == null) {
            throw new FileNotFoundException("URI is null");
        }

        if (!"r".equals(mode)) {
            throw new FileNotFoundException(
                    "ExpenseSupportingDocumentTestProvider supports read-only mode"
            );
        }

        if (getContext() == null) {
            throw new FileNotFoundException("Provider context unavailable");
        }

        String value = uri.getQueryParameter("value");

        if (value == null) {
            value = uri.getLastPathSegment();
        }

        if (value == null) {
            value = "";
        }

        File directory = new File(
                getContext().getCacheDir(),
                "expense-supporting-document-provider"
        );

        if (!directory.exists() && !directory.mkdirs()) {
            throw new FileNotFoundException(
                    "Could not create test-provider directory"
            );
        }

        File file;

        try {
            file = File.createTempFile(
                    "expense-supporting-document-",
                    ".bin",
                    directory
            );

            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(value.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (IOException error) {
            FileNotFoundException wrapped =
                    new FileNotFoundException(
                            "Could not create test-provider evidence"
                    );

            wrapped.initCause(error);
            throw wrapped;
        }

        return ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY
        );
    }

    @Override
    public String getType(Uri uri) {
        if (uri == null) {
            return "application/octet-stream";
        }

        String kind = uri.getLastPathSegment();

        if ("jpeg".equals(kind)) {
            return "image/jpeg";
        }

        if ("pdf".equals(kind)) {
            return "application/pdf";
        }

        return "application/octet-stream";
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int delete(
            Uri uri,
            String selection,
            String[] selectionArgs
    ) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs
    ) {
        throw new UnsupportedOperationException("Read only");
    }
}